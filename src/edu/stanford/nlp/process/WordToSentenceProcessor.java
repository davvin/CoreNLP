package edu.stanford.nlp.process;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import edu.stanford.nlp.io.EncodingPrintWriter;
import edu.stanford.nlp.ling.Document;
import edu.stanford.nlp.ling.HasWord;
import edu.stanford.nlp.ling.CoreAnnotations;
import edu.stanford.nlp.ling.MultiTokenTag;
import edu.stanford.nlp.util.CoreMap;
import edu.stanford.nlp.util.Generics;

// todo [cdm Feb 2012]: Rewrite the Set's as List's since while conceptually
// sets, we just don't need to be hashing things here!
// todo [cdm Aug 2012]: This should be unified with the PlainTextIterator in
// DocumentPreprocessor, perhaps by making this one implement Iterator

/**
 * Transforms a Document of Words into a Document of Sentences by grouping the
 * Words.  The word stream is assumed to already be adequately tokenized,
 * and this class just divides the list into sentences, perhaps discarding
 * some separator tokens based on the setting of the following three sets:
 * <ul>
 * <li>sentenceBoundaryTokens are tokens that are left in a sentence, but are
 * to be regarded as ending a sentence.  A canonical example is a period.
 * If two of these follow each other, the second will be a sentence
 * consisting of only the sentenceBoundaryToken.
 * <li>sentenceBoundaryFollowers are tokens that are left in a sentence, and
 * which can follow a sentenceBoundaryToken while still belonging to
 * the previous sentence.  They cannot begin a sentence (except at the
 * beginning of a document).  A canonical example is a close parenthesis
 * ')'.
 * <li>sentenceBoundaryToDiscard are tokens which separate sentences and
 * which should be thrown away.  In web documents, a typical example would
 * be a '{@code <p>}' tag.  If two of these follow each other, they are
 * coalesced: no empty Sentence is output.  The end-of-file is not
 * represented in this Set, but the code behaves as if it were a member.
 * <li>sentenceRegionBeginPattern A regular expression for marking the start
 * of a sentence region.  Not included in the sentence.
 * <li>sentenceRegionEndPattern A regular expression for marking the end
 * of a sentence region.  Not included in the sentence.
 * </ul>
 * See DocumentPreprocessor for a class with a main method that will call this
 * and cut a text file up into sentences.
 *
 * @author Joseph Smarr (jsmarr@stanford.edu)
 * @author Christopher Manning
 * @author Teg Grenager (grenager@stanford.edu)
 * @author Sarah Spikes (sdspikes@cs.stanford.edu) (Templatization)
 *
 * @param <IN> The type of the tokens in the sentences
 */
public class WordToSentenceProcessor<IN> implements ListProcessor<IN, List<IN>> {

  private static final boolean DEBUG = false;

  public static final Set<String> DEFAULT_BOUNDARY_FOLLOWERS = Collections.unmodifiableSet(Generics.newHashSet(Arrays.asList(")", "]", "\"", "\'", "''", "-RRB-", "-RSB-", "-RCB-")));
  public static final Set<String> DEFAULT_SENTENCE_BOUNDARIES_TO_DISCARD = Collections.unmodifiableSet(Generics.newHashSet(Arrays.asList(WhitespaceLexer.NEWLINE, PTBLexer.NEWLINE_TOKEN)));

  /**
   * Regex for tokens (Strings) that qualify as sentence-final tokens.
   */
  private final Pattern sentenceBoundaryTokenPattern;

  /**
   * Set of tokens (Strings) that qualify as tokens that can follow
   * what normally counts as an end of sentence token, and which are
   * attributed to the preceding sentence.  For example ")" coming after
   * a period.
   */
  private final Set<String> sentenceBoundaryFollowers;

  /**
   * List of regex Pattern that are sentence boundaries to be discarded.
   */
  private List<Pattern> sentenceBoundaryToDiscard;

  private final Pattern sentenceRegionBeginPattern;

  private final Pattern sentenceRegionEndPattern;

  private boolean isOneSentence;

  private boolean allowEmptySentences = false;

  public void setSentenceBoundaryToDiscard(Set<String> regexSet) {
    sentenceBoundaryToDiscard = new ArrayList<Pattern>(regexSet.size());
    for (String s: regexSet) {
      sentenceBoundaryToDiscard.add(Pattern.compile(Pattern.quote(s)));
    }
  }

  public boolean isOneSentence() {
    return isOneSentence;
  }

  public void setOneSentence(boolean oneSentence) {
    isOneSentence = oneSentence;
  }

  public boolean allowEmptySentences() {
    return allowEmptySentences;
  }

  public void setAllowEmptySentences(boolean allowEmptySentences) {
    this.allowEmptySentences = allowEmptySentences;
  }

  public void addHtmlSentenceBoundaryToDiscard(Set<String> set) {
    if (sentenceBoundaryToDiscard == null) {
      sentenceBoundaryToDiscard = new ArrayList<Pattern>();
    }
    for (String s: set) {
      sentenceBoundaryToDiscard.add(Pattern.compile("<\\s*/?\\s*" + s + "\\s*/?\\s*>", Pattern.CASE_INSENSITIVE));
      sentenceBoundaryToDiscard.add(Pattern.compile("<\\s*" + s + "\\s+[^>]+>", Pattern.CASE_INSENSITIVE));
    }
  }

  private boolean matchesSentenceBoundaryToDiscard(String word) {
    for(Pattern p: sentenceBoundaryToDiscard){
      Matcher m = p.matcher(word);
      if(m.matches()){
        return true;
      }
    }
    return false;
  }

  @Override
  public List<List<IN>> process(List<? extends IN> words) {
    if (isOneSentence) {
      List<List<IN>> sentences = Generics.newArrayList();
      sentences.add(new ArrayList<IN>(words));
      return sentences;
    } else {
      return wordsToSentences(words);
    }
  }

  /**
   * Returns a List of Lists where each element is built from a run
   * of Words in the input Document. Specifically, reads through each word in
   * the input document and breaks off a sentence after finding a valid
   * sentence boundary token or end of file.
   * Note that for this to work, the words in the
   * input document must have been tokenized with a tokenizer that makes
   * sentence boundary tokens their own tokens (e.g., {@link PTBTokenizer}).
   *
   * @param words A list of already tokenized words (must implement HasWord or be a String)
   * @return A list of Sentence
   * @see #WordToSentenceProcessor(String, Set, Set, Pattern, Pattern)
   */
  public List<List<IN>> wordsToSentences(List<? extends IN> words) {
    List<List<IN>> sentences = Generics.newArrayList();
    List<IN> currentSentence = new ArrayList<IN>();
    List<IN> lastSentence = null;
    boolean insideRegion = false;
    boolean inWaitForForcedEnd = false;
    for (IN o: words) {
      String word;
      if (o instanceof HasWord) {
        HasWord h = (HasWord) o;
        word = h.word();
      } else if (o instanceof String) {
        word = (String) o;
      } else if (o instanceof CoreMap) {
        word = ((CoreMap)o).get(CoreAnnotations.TextAnnotation.class);
      } else {
        throw new RuntimeException("Expected token to be either Word or String.");
      }

      boolean forcedEnd = false;
      boolean inMultiTokenExpr = false;
      if (o instanceof CoreMap) {
        CoreMap cm = (CoreMap) o;
        Boolean forcedEndValue = cm.get(CoreAnnotations.ForcedSentenceEndAnnotation.class);
        Boolean forcedUntilEndValue = cm.get(CoreAnnotations.ForcedSentenceUntilEndAnnotation.class);
        if (forcedEndValue != null)
          forcedEnd = forcedEndValue;
        else if (forcedUntilEndValue != null && forcedUntilEndValue)
          inWaitForForcedEnd = true;
        else {
          MultiTokenTag mt = cm.get(CoreAnnotations.MentionTokenAnnotation.class);
          if (mt != null && !mt.isEnd()) {
            // In the middle of a multi token mention, make sure sentence is not ended here
            inMultiTokenExpr = true;
          }
        }
      }


        if (DEBUG) {
        EncodingPrintWriter.err.println("Word is " + word, "UTF-8");
      }
      if (sentenceRegionBeginPattern != null && ! insideRegion) {
        if (sentenceRegionBeginPattern.matcher(word).matches()) {
          insideRegion = true;
        }
        if (DEBUG) {
          System.err.println("  outside region");
        }
        continue;
      }
      if (sentenceBoundaryFollowers.contains(word) && lastSentence != null && currentSentence.isEmpty()) {
        lastSentence.add(o);
        if (DEBUG) {
          System.err.println("  added to last");
        }
      } else {
        boolean newSent = false;
        if (inWaitForForcedEnd && !forcedEnd) {
          currentSentence.add(o);
          if (DEBUG) {
            System.err.println("  is in wait for forced end; added to current");
          }
        } else if (inMultiTokenExpr && !forcedEnd) {
          currentSentence.add(o);
          if (DEBUG) {
            System.err.println("  is in multi token expr; added to current");
          }
        } else if (matchesSentenceBoundaryToDiscard(word)) {
          newSent = true;
        } else if (sentenceRegionEndPattern != null && sentenceRegionEndPattern.matcher(word).matches()) {
          insideRegion = false;
          newSent = true;
        } else if (sentenceBoundaryTokenPattern.matcher(word).matches()) {
          currentSentence.add(o);
          if (DEBUG) {
            System.err.println("  is sentence boundary; added to current");
          }
          newSent = true;
        } else if (forcedEnd) {
          currentSentence.add(o);
          inWaitForForcedEnd = false;
          newSent = true;
          if (DEBUG) {
            System.err.println("  annotated to be the end of a sentence");
          }
        } else {
          currentSentence.add(o);
          if (DEBUG) {
            System.err.println("  added to current");
          }
        }
        if (newSent && (!currentSentence.isEmpty() || allowEmptySentences())) {
          if (DEBUG) {
            System.err.println("  beginning new sentence");
          }
          sentences.add(currentSentence);
          // adds this sentence now that it's complete
          lastSentence = currentSentence;
          currentSentence = new ArrayList<IN>(); // clears the current sentence
        }
      }
    }

    // add any words at the end, even if there isn't a sentence
    // terminator at the end of file
    if ( ! currentSentence.isEmpty()) {
      sentences.add(currentSentence); // adds last sentence
    }
    return sentences;
  }


  public <L, F> Document<L, F, List<IN>> processDocument(Document<L, F, IN> in) {
    Document<L, F, List<IN>> doc = in.blankDocument();
    doc.addAll(process(in));
    return doc;
  }

  /**
   * Create a {@code WordToSentenceProcessor} using a sensible default
   * list of tokens to split on for English/Latin writing systems.
   * The default set is: {".","?","!"} and
   * any combination of ! or ?, as in !!!?!?!?!!!?!!?!!!.
   */
  public WordToSentenceProcessor() {
    this("\\.|[!?]+");
  }

  /**
   * Flexibly set the set of acceptable sentence boundary tokens, but with
   * a default set of allowed boundary following tokens and sentence boundary
   * to discard tokens (based on English and Penn Treebank encoding).
   * The allowed set of boundary followers is:
   * {")","]","\"","\'", "''", "-RRB-", "-RSB-", "-RCB-"}.
   * The default set of discarded separator tokens includes the
   * newline tokens used by WhitespaceLexer and PTBLexer.
   *
   * @param boundaryTokenRegex The set of boundary tokens
   */
  public WordToSentenceProcessor(String boundaryTokenRegex) {
    this(boundaryTokenRegex, DEFAULT_BOUNDARY_FOLLOWERS, DEFAULT_SENTENCE_BOUNDARIES_TO_DISCARD);
  }

  /**
   * Flexibly set the set of acceptable sentence boundary tokens,
   * the set of tokens commonly following sentence boundaries, and also
   * the set of tokens that are sentences boundaries that should be
   * discarded.
   */
  public WordToSentenceProcessor(String boundaryTokenRegex,
                                 Set<String> boundaryFollowers,
                                 Set<String> boundaryToDiscard) {
    this(boundaryTokenRegex, boundaryFollowers, boundaryToDiscard, null, null);
  }

  /**
   * Flexibly set a pattern that matches acceptable sentence boundaries,
   * the set of tokens commonly following sentence boundaries, and also
   * the set of tokens that are sentence boundaries that should be discarded.
   * This is private because it is a dangerous constructor. It's not clear what the semantics
   * should be if there are both boundary token sets, and patterns to match.
   */
  private WordToSentenceProcessor(String boundaryTokenRegex, Set<String> boundaryFollowers, Set<String> boundaryToDiscard, Pattern regionBeginPattern, Pattern regionEndPattern) {
    sentenceBoundaryTokenPattern = Pattern.compile(boundaryTokenRegex);
    sentenceBoundaryFollowers = boundaryFollowers;
    setSentenceBoundaryToDiscard(boundaryToDiscard);
    sentenceRegionBeginPattern = regionBeginPattern;
    sentenceRegionEndPattern = regionEndPattern;
    if (DEBUG) {
      EncodingPrintWriter.err.println("WordToSentenceProcessor: boundaryTokens=" + boundaryTokenRegex, "UTF-8");
      EncodingPrintWriter.err.println("  boundaryFollowers=" + boundaryFollowers, "UTF-8");
      EncodingPrintWriter.err.println("  boundaryToDiscard=" + boundaryToDiscard, "UTF-8");
    }
  }

}
