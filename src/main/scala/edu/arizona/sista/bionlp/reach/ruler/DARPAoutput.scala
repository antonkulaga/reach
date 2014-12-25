package edu.arizona.sista.bionlp.reach.ruler


import java.io.{PrintWriter, File, BufferedReader, FileReader}
import edu.arizona.sista.matcher.{ExtractorEngine, EventMention}
import edu.arizona.sista.processors.{DocumentSerializer, Document}
import edu.arizona.sista.processors.bionlp.BioNLPProcessor
import RuleShell.Repr
/**
 * Created by gus on 12/22/14.
 */
class DARPAoutput {
}

object DARPAoutput extends App {

  val entityRules = Ruler.readEntityRules
  val eventRules = Ruler.readEventRules
  val rules = entityRules + "\n\n" + eventRules

  val ds = new DocumentSerializer

  val actions = new DarpaActions

  val proc = new BioNLPProcessor()
  val extractor = new ExtractorEngine(rules, actions)

  //val text = "We hypothesized that the MEK / ERK pathway may suppress trans-phosphorylation of ERBB3 by directly phosphorylating the JM domains of EGFR and HER2 , and that this could be a dominant MEK inhibitor induced feedback leading to AKT activation in these cancers ."
  //val doc = proc.annotate(text)
  val outName = s"${System.getProperty("user.home")}/Desktop/DARPAout.csv"
  val output = new PrintWriter(new File(outName))

  val dir = "/Users/gus/github/reach/src/main/resources/edu/arizona/sista/bionlp/extractors/papers/"
  val papers = Seq(docFromSerializedFile(s"$dir/MEKinhibition.txt.ser"), docFromSerializedFile(s"$dir/UbiquitinationofRas.txt.ser"))
  val passageID = Map(papers.head -> 1, papers.last -> 2)

  val header = s"Mention Count;Relation;Model Link (BioPax or BEL);‘English-like’ Description;Model Representation;Source Text\n"

  println(s"Writing output to $outName")
  output.write(header)
  val mentions: Map[String, Seq[EventMention]] =
    papers
      .map(d => retrieveMentions(d))
      .flatten
      .groupBy(_.repr)
  mentions.foreach(pair => displayEvents(pair._1, pair._2))

  def docFromSerializedFile(filename: String): Document = {
    val br = new BufferedReader(new FileReader(filename))
    val doc = ds.load(br)
    doc
  }

  def retrieveMentions(doc: Document): Seq[EventMention] = {
    extractor.extractFrom(doc).filter(_.isInstanceOf[EventMention]).map(_.asInstanceOf[EventMention])
  }

  def displayEvents(representation:String, mentions:Seq[EventMention]) {
    def getText:String = {
      mentions.sortBy(m => (passageID(m.document), m.sentence, m.start)) // sort by doc, sentence, start idx
        .map(m => m.document.sentences(m.sentence).getSentenceText()) // get text
        .mkString("  ")
    }
    output.write(s"${mentions.size};;;;$representation;$getText\n")
  }
}


