package org.goobs.naturalli

import scala.collection.mutable
import scala.collection.JavaConversions._

import edu.stanford.nlp._
import edu.stanford.nlp.util.logging.Redwood
import edu.stanford.nlp.util.logging.Redwood.Util._

import edu.smu.tspell.wordnet.{Synset, SynsetType}

import org.goobs.naturalli.Implicits._
import org.goobs.naturalli.Postgres.{slurpTable, TABLE_WORD_INTERN}
import java.sql.ResultSet

object Utils {

  NLPConfig.truecase.bias = "INIT_UPPER:-0.7,UPPER:-2.5,O:0"
  private val logger = Redwood.channels("Utils")

  val percent = new java.text.DecimalFormat("0.00%")
  val df = new java.text.DecimalFormat("0.0000")
  val short = new java.text.DecimalFormat("0.00")

  // Note: match these with CreateGraph's index creation
  val WORD_NONE:String = "__none__"
  val WORD_UNK:String  = "__unk__"
  val AUXILLIARY_VERBS = Set[String](
    "be",
    "can",
    "could",
    "dare",
    "do",
    "have",
    "may",
    "might",
    "must",
    "need",
    "ought",
    "shall",
    "should",
    "will",
    "would"
  )

  val INTENSIONAL_ADJECTIVES = Set[String](
    "believed",
    "debatable",
    "disputed",
    "dubious",
    "hypothetical",
    "impossible",
    "improbable",
    "plausible",
    "putative",
    "questionable",
    "so called",
    "supposed",
    "suspicious",
    "theoretical",
    "uncertain",
    "unlikely",
    "would - be",
    "apparent",
    "arguable",
    "assumed",
    "likely",
    "ostensible",
    "possible",
    "potential",
    "predicted",
    "presumed",
    "probable",
    "seeming",
    "anti",
    "fake",
    "fictional",
    "fictitious",
    "imaginary",
    "mythical",
    "phony",
    "false",
    "artificial",
    "erroneous",
    "mistaken",
    "mock",
    "pseudo",
    "simulated",
    "spurious",
    "deputy",
    "faulty",
    "virtual",
    "doubtful",
    "erstwhile",
    "ex",
    "expected",
    "former",
    "future",
    "onetime",
    "past",
    "proposed"
  )

  def mkUNK(identifier:Int):String = {
    if (identifier >= 10 || identifier < 0) {
      throw new IllegalArgumentException("Can only instantiate up to 10 UNK types")
    }
    "__unk[" + identifier + "]__"
  }

  /**
   * Creates a new unknown word provider.
   * This should be created on every (antecedent, consequent) pair to ensure that
   * the antecedents and consequents map to the same unknown word if the words
   * are equal.
   */
  def newUnkProvider:String=>String = {
    val unkMapping = new mutable.HashMap[String, Int]
    (w:String) => {
      unkMapping.get(w) match {
        case Some(index) => Utils.mkUNK(index)
        case None =>
          unkMapping(w) = unkMapping.size
          Utils.mkUNK(unkMapping(w))
      }
    }
  }

  def pos2synsetType(pos:String):SynsetType = pos match {
    case r"""[Nn].*""" => SynsetType.NOUN
    case r"""[Vv].*""" => SynsetType.VERB
    case r"""[Jj].*""" => SynsetType.ADJECTIVE
    case r"""[Rr].*""" => SynsetType.ADVERB
    case _ => logger.debug("Unknown POS: " + pos); SynsetType.NOUN
  }
  
  val Whitespace = """\s+""".r
  val Roman_Numeral = """^M{0,4}(CM|CD|D?C{0,3})(XC|XL|L?X{0,3})(IX|IV|V?I{0,3})$""".r
  
  def tokenizeWithCase(phrase:Array[String], headWord:Option[String=>Any]=None):Array[String] = {
    // Construct a fake sentence
    val offset = 3
    val lowercaseWords = phrase.map( _.toLowerCase )
    val lowercaseSent = new Array[String](lowercaseWords.length + 6)
    System.arraycopy(lowercaseWords, 0, lowercaseSent, offset, lowercaseWords.length)
    lowercaseSent(0) = "we"
    lowercaseSent(1) = "see"
    lowercaseSent(2) = "that"
    lowercaseSent(lowercaseWords.length + offset + 0) = "is"
    lowercaseSent(lowercaseWords.length + offset + 1) = "blue"
    lowercaseSent(lowercaseWords.length + offset + 2) = "."
    val sentence = Sentence(lowercaseSent)
    for (fn <- headWord) { 
      if (phrase.length == 0) { }
      else if (phrase.length == 1) { fn(phrase(0)) }
      else {
        val headIndex = sentence.headIndex(offset, offset + phrase.length)
        fn(phrase(offset + headIndex))
      }
    }
    // Tokenize
    if (lowercaseWords.length == 0) { 
      new Array[String](0)
    } else {
      sentence.truecase.slice(offset, offset + phrase.length)
    }
  }
  
  def tokenizeWithCase(phrase:String):Array[String] = {
    tokenizeWithCase(Sentence(phrase).lemma, None)
  }
  
  def tokenizeWithCase(phrase:String, headWord:String=>Any):Array[String] = {
    tokenizeWithCase(Sentence(phrase).lemma, Some(headWord))
  }

  def printToFile(f: java.io.File)(op: java.io.PrintWriter => Any) {
    val p = new java.io.PrintWriter(f)
    try { op(p) } finally { p.close() }
  }

  def index(rawPhrase:String, doHead:Boolean=false, allowEmpty:Boolean=false)
           (implicit contains:String=>Boolean, wordIndexer:String=>Int, unkProvider:String=>String) :(Array[Int],Int) = {
    if (!allowEmpty && rawPhrase.trim.equals("")) { return (Array[Int](), 0) }
    var headWord:Option[String] = None
    val phrase:Array[String]
    = if (doHead && Props.SCRIPT_REVERB_HEAD_DO) tokenizeWithCase(rawPhrase, (hw:String) => headWord = Some(hw))
    else tokenizeWithCase(rawPhrase)
    // Create object to store result
    val indexResult:Array[Int] = new Array[Int](phrase.length)
    for (i <- 0 until indexResult.length) { indexResult(i) = -1 }

    for (length <- phrase.length until 0 by -1;
         start <- 0 to phrase.length - length) {
      var found = false
      // Find largest phrases to index into (case sensitive)
      if ( (start until (start + length)) forall (indexResult(_) < 0) ) {
        val candidate:String = phrase.slice(start, start+length).mkString(" ")
        if (contains(candidate)) {
          val index = wordIndexer(candidate)
          for (i <- start until start + length) { indexResult(i) = index; }
          found = true
        }
        if (length > 1 && !found) {
          // Try to title-case (if it was title cased to begin with)
          val candidate:String = phrase.slice(start, start+length)
            .map( (w:String) => if (w.length <= 1) w.toUpperCase
          else w.substring(0, 1).toUpperCase + w.substring(1) )
            .mkString(" ")
          if (rawPhrase.contains(candidate) &&  // not _technically_ sufficient, but close enough
            contains(candidate)) {
            val index = wordIndexer(candidate)
            for (i <- start until start + length) { indexResult(i) = index; }
            found = true
          }
          if (!found) {
            // Try to lower-case
            if (contains(candidate.toLowerCase)) {
              val index = wordIndexer(candidate.toLowerCase)
              for (i <- start until start + length) { indexResult(i) = index; }
              found = true
            }
          }
        }
      }
    }

    // Find any dangling singletons
    for (length <- phrase.length until 0 by -1;
         start <- 0 to phrase.length - length) {
      if ( (start until (start + length)) forall (indexResult(_) < 0) ) {
        val candidate:String = phrase.slice(start, start+length).mkString(" ")
        if (contains(candidate.toLowerCase)) {
          val index = wordIndexer(candidate.toLowerCase)
          for (i <- start until start + length) { indexResult(i) = index; }
        }
      }
    }

    // Find head word index
    val headWordIndexed:Int =
      (for (hw <- headWord) yield {
        if (contains(hw)) { Some(wordIndexer(hw)) }
        else if (contains(hw.toLowerCase)) { Some(wordIndexer(hw.toLowerCase)) }
        else { None }
      }).flatten.getOrElse(-1)

    // Create resulting array
    var lastElem:Int = -999
    var rtn = List[Int]()
    for (i <- indexResult.length - 1 to 0 by -1) {
      if (indexResult(i) < 0) { indexResult(i) = wordIndexer(unkProvider(phrase(i))) }
      if (indexResult(i) != lastElem) {
        lastElem = indexResult(i)
        rtn = indexResult(i) :: rtn
      }
    }
    (rtn.toArray, headWordIndexed)
  }

  lazy val (wordIndexer, wordGloss):(mutable.HashMap[String,Int],Array[String]) = {
    startTrack("Reading Word Index")
    val wordIndexer = new mutable.HashMap[String,Int]
    var count = 0
    slurpTable(TABLE_WORD_INTERN, {(r:ResultSet) =>
      val key:Int = r.getInt("index")
      val gloss:String = r.getString("gloss")
      wordIndexer(gloss) = key
      count += 1
      if (count % 1000000 == 0) {
        logger.log("read " + (count / 1000000) + "M words; " + (Runtime.getRuntime.freeMemory / 1000000) + " MB of memory free")
      }
    })
    log("read " + count + " words")
    endTrack("Reading Word Index")
    val reverseIndex = new Array[String](wordIndexer.size)
    wordIndexer.foreach{ case (p1:String, p2:Int) =>
      reverseIndex(p2) = p1
    }
    (wordIndexer, reverseIndex)
  }

  /**
   * A mapping from (word_index, definition) -> word sense
   */
  lazy val (senseIndex, senseGloss):(Map[(Int, String), Int],Map[(Int,Int), String]) = {
    var elems = List[((Int, String), Int)]()
    var glosses = List[((Int, Int), String)]()
    slurpTable(Postgres.TABLE_WORD_SENSE, {(r:ResultSet) =>
      elems = ((r.getInt("index"), r.getString("definition")), r.getInt("sense")) :: elems
      glosses = ((r.getInt("index"), r.getInt("sense")), r.getString("definition")) :: glosses
    })
    (elems.toMap, glosses.toMap)
  }

  def simplifyQuery(query:Messages.Query, annotate:String=>Messages.Fact):Messages.Query = {
    simplifyQuery(query.getQueryFact.getWordList.map( _.getGloss.replaceAll("""__num\([0-9]*\)__""", "7").replaceAll(WORD_UNK, "foobazle") ).mkString(" "), annotate) match {
      case Some(fact) => Messages.Query.newBuilder(query).setQueryFact(annotate(fact)).setAllowLookup(true).build()
      case None => query
    }
  }

  def simplifyQuery(gloss:String, annotate:String=>Messages.Fact):Option[String] = {
    val sentence = new Sentence(gloss)
    def hackyHead(input:String, prefix:Char='N', force:Boolean=false):Option[String] = {
      val sentence = new Sentence(input)
      val seq = sentence.pos.zip(sentence.lemma)
      val goodGuess = seq.takeWhile{ case (pos:String, lemma:String) => pos(0) != 'P' && pos != "IN" }
        .reverse
        .find{ case (pos:String, lemma:String) => pos(0) == prefix && !AUXILLIARY_VERBS(lemma) }
        .map{ _._2 }
      if (force) {
        goodGuess.orElse(seq.find(_._1(0) == 'N').map( _._2 )).orElse( if (seq.length > 0) Some(seq(seq.length - 1)._2) else None )
      } else {
        goodGuess
      }
    }
    val quantifiers: Array[String] = Quantifier.values.map{ _.surfaceForm.mkString(" ") }
    def mkFact(left:String, verb:Int, right:String, force:Boolean = false):Option[String] = {
      for ( subj <- hackyHead(left, 'N').orElse(hackyHead(left, 'V', force));
            rel  <- Some(sentence.lemma(verb));
            obj  <- hackyHead(right, 'N', force) ) yield {
        def quantifier(elem:String, head:String):String = {
          quantifiers.find((x: String) => elem.toLowerCase.startsWith(x + " ")) match {
            case Some(q) => if (head.startsWith(q) || q.startsWith(head)) "" else q + " "
            case None =>
              ""
          }
        }
        (quantifier(left, subj) + subj + " " + rel + " " + quantifier(right, obj) + obj).toLowerCase
      }

    }
    // Try and find the main verb via dependency parsing
    val verb = {
      val naiveRoot = sentence.dependencyRoot
      val lessNaiveRoot = if (naiveRoot == 0 || sentence.pos(naiveRoot)(0) != 'V') sentence.stanfordDependencies.indexWhere{ case (i, l) => i == 0 } else naiveRoot
      if (lessNaiveRoot < 0 || sentence.pos(lessNaiveRoot)(0) != 'V') 0 else lessNaiveRoot
    }
    val left = sentence.word.slice(0, verb).mkString(" ")
    val right = sentence.word.zip(sentence.pos).drop(verb + 1).dropWhile( x => x._2(0) == 'P' || x._2 == "IN" || x._2 == "RB" || x._2 == "TO").map( _._1 ).mkString(" ")
    mkFact(left, verb, right).orElse({
      // Else, try and find the main verb via POS matching
      var mainVerb: Int = sentence.pos.zip(sentence.lemma).indexWhere{ case (p, l) => p(0) == 'V' && !AUXILLIARY_VERBS(l) && p(p.length-1) != 'G'}
      if (mainVerb < 0) { mainVerb = sentence.pos.zip(sentence.lemma).indexWhere{ case (p, l) => p(0) == 'V' && p(p.length-1) != 'G'} }
      if (mainVerb < 0) { mainVerb = sentence.pos.zip(sentence.lemma).indexWhere{ case (p, l) => p(0) == 'V' } }
      if (mainVerb < 0 && sentence.length >= 3) { mainVerb = 1 }
      if (mainVerb > 0) {
        val left = sentence.word.slice(0, mainVerb).mkString(" ")
        val right = sentence.word.zip(sentence.pos).drop(mainVerb + 1).dropWhile( x => x._2(0) == 'P' || x._2 == "IN" || x._2 == "RB" || x._2 == "TO").map( _._1 ).mkString(" ")
        val fact = mkFact(left, mainVerb, right).orElse(mkFact(left, mainVerb, right, force = true))
        if (!fact.isDefined) {
          warn("could not simplify fact [1]: " + gloss)
        }
        fact
      } else {
        warn("could not simplify fact [2]: " + gloss)
        None
      }
    })
  }

}

object TruthValue extends Enumeration {
  type TruthValue = Value
  val TRUE    = Value(0,  "true")
  val FALSE   = Value(1,  "false")
  val UNKNOWN = Value(2,  "unknown")
  val INVALID = Value(3,  "invalid")

  def asBoolean:Boolean = Value match {
    case TRUE => true
    case FALSE => false
    case UNKNOWN => false
    case _ => throw new IllegalStateException("Cannot collapse truth value: " + Value)
  }

}

object EdgeType extends Enumeration {

  def toMacCartneyRelation(t:EdgeType):String = {
    t match {
      case WORDNET_UP => ">"
      case FREEBASE_UP => ">"
      case QUANTIFIER_UP => ">"
      case DEL_NOUN => ">"
      case DEL_VERB => ">"
      case DEL_ADJ => ">"
      case DEL_OTHER => ">"

      case WORDNET_DOWN => "<"
      case FREEBASE_DOWN => "<"
      case QUANTIFIER_DOWN => "<"
      case ADD_NOUN => "<"
      case ADD_VERB => "<"
      case ADD_ADJ => "<"
      case ADD_OTHER => "<"

      case WORDNET_NOUN_ANTONYM => "|"
      case WORDNET_VERB_ANTONYM => "|"
      case WORDNET_ADJECTIVE_ANTONYM => "|"
      case WORDNET_ADVERB_ANTONYM => "|"

      case WORDNET_NOUN_SYNONYM => "="
      case WORDNET_ADJECTIVE_RELATED => "="
      case QUANTIFIER_REWORD => "="
      case ANGLE_NEAREST_NEIGHBORS => "="
      case MORPH_FUDGE_NUMBER => "="
      case SENSE_REMOVE => "="
      case SENSE_ADD => "="
      case WORDNET_ADJECTIVE_PERTAINYM => "="
      case WORDNET_ADVERB_PERTAINYM => "="

      case ADD_NEGATION => "!"
      case DEL_NEGATION => "!"
      case QUANTIFIER_NEGATE => "!"

      // The weird cases...
      case ADD_EXISTENTIAL => "="
      case ADD_UNIVERSAL => "="
      case ADD_QUANTIFIER_OTHER => "="
      case DEL_EXISTENTIAL => "="
      case DEL_UNIVERSAL => "="
      case DEL_QUANTIFIER_OTHER => "="
      case _ => "?"
    }
  }

  type EdgeType = Value
  val WORDNET_UP                     = Value(0,  "wordnet_up")
  val WORDNET_DOWN                   = Value(1,  "wordnet_down")
  val WORDNET_NOUN_ANTONYM           = Value(2,  "wordnet_noun_antonym")
  val WORDNET_NOUN_SYNONYM           = Value(3,  "wordnet_noun_synonym")
  val WORDNET_VERB_ANTONYM           = Value(4,  "wordnet_verb_antonym")
  val WORDNET_ADJECTIVE_ANTONYM      = Value(5,  "wordnet_adjective_antonym")
  val WORDNET_ADVERB_ANTONYM         = Value(6,  "wordnet_adverb_antonym")
  val WORDNET_ADJECTIVE_PERTAINYM    = Value(7,  "wordnet_adjective_pertainym")
  val WORDNET_ADVERB_PERTAINYM       = Value(8,  "wordnet_adverb_pertainym")
  val WORDNET_ADJECTIVE_RELATED      = Value(9,  "wordnet_adjective_related")
  
  val ANGLE_NEAREST_NEIGHBORS        = Value(10,  "angle_nn")
  
  val FREEBASE_UP                    = Value(11, "freebase_up")
  val FREEBASE_DOWN                  = Value(12, "freebase_down")

  val ADD_NOUN                       = Value(13, "add_noun")
  val ADD_VERB                       = Value(14, "add_verb")
  val ADD_ADJ                        = Value(15, "add_adj")
  val ADD_NEGATION                   = Value(16, "add_negation")
  val ADD_EXISTENTIAL                = Value(17, "add_existential")
  val ADD_QUANTIFIER_OTHER           = Value(18, "add_quantifier_other")
  val ADD_UNIVERSAL                  = Value(19, "add_universal")
  val ADD_OTHER                      = Value(20, "add_?")

  val DEL_NOUN                       = Value(21, "del_noun")
  val DEL_VERB                       = Value(22, "del_verb")
  val DEL_ADJ                        = Value(23, "del_adj")
  val DEL_NEGATION                   = Value(24, "del_negation")
  val DEL_EXISTENTIAL                = Value(25, "del_existential")
  val DEL_QUANTIFIER_OTHER           = Value(26, "del_quantifier_other")
  val DEL_UNIVERSAL                  = Value(27, "del_universal")
  val DEL_OTHER                      = Value(28, "del_?")

  // Quantifiers
  val QUANTIFIER_UP                  = Value(29, "quantifier_up")
  val QUANTIFIER_DOWN                = Value(30, "quantifier_down")
  // --------
  // NOTE: Everything under here is monotonicity agnostic
  // --------
  val MONOTONE_INDEPENDENT_BEGIN:Int = 31
  val QUANTIFIER_NEGATE              = Value(31, "quantifier_negate")
  val QUANTIFIER_REWORD              = Value(32, "quantifier_reword")

  // Could in theory be subdivided: tense, plurality, etc.
  val MORPH_FUDGE_NUMBER             = Value(33, "morph_fudge_number")

  // Word Sense Disambiguation
  val SENSE_REMOVE                   = Value(34, "sense_remove")
  val SENSE_ADD                      = Value(35, "sense_add")
}