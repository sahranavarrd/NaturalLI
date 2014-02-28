package org.goobs.truth

import scala.collection.JavaConversions._

/**
 * Tests for various NatLog functionalities
 *
 * @author gabor
 */
class NatLogTest extends Test {

  describe("Natural Logic Weights") {
    describe("when a hard assignment") {
      it ("should accept Wordnet monotone jumps") {
        NatLog.hardNatlogWeights.getCount(Learn.unigramUp(EdgeType.WORDNET_UP)) should be >= -1.0
        NatLog.hardNatlogWeights.getCount(Learn.unigramDown(EdgeType.WORDNET_DOWN)) should be >= -1.0
        NatLog.hardNatlogWeights.getCount(Learn.bigramUp(EdgeType.WORDNET_UP, EdgeType.WORDNET_UP)) should be >= -1.0
        NatLog.hardNatlogWeights.getCount(Learn.bigramDown(EdgeType.WORDNET_DOWN, EdgeType.WORDNET_DOWN)) should be >= -1.0
      }
      it ("should accept Freebase monotone jumps") {
        NatLog.hardNatlogWeights.getCount(Learn.unigramUp(EdgeType.FREEBASE_UP)) should be >= -1.0
        NatLog.hardNatlogWeights.getCount(Learn.unigramDown(EdgeType.FREEBASE_DOWN)) should be >= -1.0
        NatLog.hardNatlogWeights.getCount(Learn.bigramUp(EdgeType.FREEBASE_UP, EdgeType.FREEBASE_UP)) should be >= -1.0
        NatLog.hardNatlogWeights.getCount(Learn.bigramDown(EdgeType.FREEBASE_DOWN, EdgeType.FREEBASE_DOWN)) should be >= -1.0
      }
      it ("should accept hybrid monotone jumps") {
        NatLog.hardNatlogWeights.getCount(Learn.bigramUp(EdgeType.WORDNET_UP, EdgeType.FREEBASE_UP)) should be >= -1.0
        NatLog.hardNatlogWeights.getCount(Learn.bigramDown(EdgeType.FREEBASE_DOWN, EdgeType.WORDNET_DOWN)) should be >= -1.0
      }
      it ("should NOT accept mixed jumps") {
        NatLog.hardNatlogWeights.getCount(Learn.bigramUp(EdgeType.FREEBASE_UP, EdgeType.FREEBASE_DOWN)) should be < 0.0
        NatLog.hardNatlogWeights.getCount(Learn.bigramDown(EdgeType.FREEBASE_DOWN, EdgeType.FREEBASE_UP)) should be < 0.0
        NatLog.hardNatlogWeights.getCount(Learn.bigramUp(EdgeType.WORDNET_UP, EdgeType.FREEBASE_DOWN)) should be < 0.0
        NatLog.hardNatlogWeights.getCount(Learn.bigramDown(EdgeType.FREEBASE_DOWN, EdgeType.WORDNET_UP)) should be < 0.0
      }
    }
  }


  describe("Monotonicity Markings") {
    import Messages.Monotonicity._
    it ("should mark 'all'") {
      NatLog.annotate("all cats", "have", "tails").getWordList.map( _.getMonotonicity ) should be (List(DOWN, DOWN, UP, UP))
      NatLog.annotate("every cat", "has", "a tail").getWordList.map( _.getMonotonicity ) should be (List(DOWN, DOWN, UP, UP, UP))
    }
    it ("should mark 'some'") {
      NatLog.annotate("some cats", "have", "tails").getWordList.map( _.getMonotonicity ) should be (List(UP, UP, UP, UP))
      NatLog.annotate("there are cats", "which have", "tails").getWordList.map( _.getMonotonicity ) should be (List(UP, UP, UP, UP, UP, UP))
      NatLog.annotate("there exist cats", "which have", "tails").getWordList.map( _.getMonotonicity ) should be (List(UP, UP, UP, UP, UP, UP))
    }
    it ("should mark 'most'") {
      NatLog.annotate("few cat", "have", "tails").getWordList.map( _.getMonotonicity ) should be (List(FLAT, FLAT, UP, UP))
      NatLog.annotate("most cats", "have", "tails").getWordList.map( _.getMonotonicity ) should be (List(FLAT, FLAT, UP, UP))
    }
    it ("should mark 'no'") {
      NatLog.annotate("no cats", "have", "tails").getWordList.map( _.getMonotonicity ) should be (List(DOWN, DOWN, DOWN, DOWN))
      NatLog.annotate("cat", "dont have", "tails").getWordList.map( _.getMonotonicity ) should be (List(DOWN, DOWN, DOWN, DOWN))
    }
  }

  describe("Word Senses") {
    it ("should get default sense of 'cat'") {
      NatLog.annotate("the cat", "have", "tail").getWordList.map( _.getPos ) should be (List("?", "n", "v", "n"))
      NatLog.annotate("the cat", "have", "tail").getWordList.map( _.getSense ) should be (List(0, 1, 2, 1))
    }
    it ("should get vehicle senses of 'CAT' with enough evidence") {
      NatLog.annotate("the cat", "be", "tracked vehicle").getWordList.map( _.getPos ) should be (List("?", "n", "v", "n"))
      NatLog.annotate("the cat", "be", "tracked vehicle").getWordList.map( _.getSense ) should be (List(0, 6, 2, 1))
    }
  }
}