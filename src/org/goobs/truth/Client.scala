package org.goobs.truth

import scala.collection.JavaConversions._

import org.goobs.truth.Messages.{Fact, Inference, Query, Response}
import java.io.{DataOutputStream, DataInputStream}
import java.net.Socket

import edu.stanford.nlp.util.logging.Redwood.Util._

/**
 * @author Gabor Angeli
 */
object Client {
  Props.NATLOG_INDEXER_LAZY = true

  /**
   * Issue a query to the search server, and block until it returns
   * @param query The query, with monotonicity markings set.
   * @return The query fact, with a truth value and confidence
   */
  def issueQuery(query:Query):Iterable[Inference] = {
    // Set up connection
    val sock = new Socket(Props.SERVER_HOST, Props.SERVER_PORT)
    val toServer = new DataOutputStream(sock.getOutputStream)
    val fromServer = new DataInputStream(sock.getInputStream)
    log("connection established with server.")

    // Write query
    query.writeTo(toServer)
    log(s"wrote query to server (size ${query.getSerializedSize}); awaiting response...")
    sock.shutdownOutput()  // signal end of transmission

    // Read response
    val response = Response.parseFrom(fromServer);
    if (response.getError) {
      throw new RuntimeException(s"Error on inference server: ${if (response.hasErrorMessage) response.getErrorMessage else ""}")
    }
    Response.parseFrom(fromServer).getInferenceList
  }

  def main(args:Array[String]):Unit = {
    val INPUT = """\s*\[([^\]]+)\]\s*\(([^,]+),\s*([^\)]+)\)\s*""".r
    val weights = NatLog.hardNatlogWeights


    do {
      println()
      println("Enter an antecedent and a consequent as \"[rel](arg1, arg2)\".")
      for (antecedent:Fact <- "[have](some cat, tail)" /*readLine("antecedent> ") */ match {
            case INPUT(rel, arg1, arg2) => Some(NatLog.annotate(arg1, rel, arg2))
            case _ => println("Could not parse antecedent"); None
          }) {
        for (consequent:Fact <- "[have](some animal, tail)" /* readLine("consequent> ") */ match {
          case INPUT(rel, arg1, arg2) => Some(NatLog.annotate(arg1, rel, arg2))
          case _ => println("Could not parse consequent"); None
        }) {
          // We have our antecedent and consequent
          val query = Query.newBuilder().setQueryFact(consequent).addKnownFact(antecedent).setUseRealWorld(false).setTimeout(Props.SEARCH_TIMEOUT).build()
          // Execute Query
          val paths = issueQuery(query)
          // Evaluate Query
          val score = Learn.evaluate(paths, weights)
          // Debug Print
          if (score > 0.5) { println("Valid") } else { println("Invalid") }
        }
      }
    } while (false)
  }
}