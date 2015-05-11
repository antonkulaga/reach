package edu.arizona.sista.bionlp.reach.ruler

import java.io.File
import edu.arizona.sista.bionlp.reach.brat.Brat
import edu.arizona.sista.odin._
import edu.arizona.sista.processors.Document

class BasicRuler(val rules: String, val actions: Actions) {
  val engine = ExtractorEngine(rules, actions, postprocess)

  def extractFrom(doc: Document): Seq[Mention] = engine.extractFrom(doc)

  def postprocess(mentions: Seq[Mention], state: State): Seq[Mention] = {
    mentions flatMap { mention =>
      mention match {
        // Do we somehow have an empty Mention?
        case m if !m.isInstanceOf[TextBoundMention] && m.arguments.values.flatten.isEmpty => Nil

        // the event mention should not be a regulation and it must contain a cause
        case m: EventMention if !m.label.endsWith("egulation") && m.arguments.contains("Cause") =>
          val controller = m.arguments("Cause")
          val someEvent = new EventMention(m.label, m.trigger, m.arguments - "Cause", m.sentence, m.document, m.keep, m.foundBy)
          val args = Map("Controller" -> controller, "Controlled" -> Seq(someEvent))
          val upreg = new RelationMention("Positive_regulation", args, m.sentence, m.document, m.keep, m.foundBy)
          Seq(upreg, someEvent)

        case m: EventMention if m.label == "Binding" && m.arguments("Theme").map(_.text).contains("Ubiquitin") =>
          val themes = m.arguments("Theme") filter (_.text != "Ubiquitin")
          if (themes.nonEmpty) {
            val ubiq = new RelationMention("Ubiquitination", Map("Theme" -> themes), m.sentence, m.document, m.keep, m.foundBy)
            Seq(ubiq)
          } else Nil

        // remove mentions of binding with single theme
        case m: EventMention if m.label == "Binding" && m.arguments("Theme").size == 1 => Nil

        case m if m.label.endsWith("egulation") => if (checkRegulationArgs(m)) Seq(m) else Nil

        case m => Seq(m)
      }
    }
  }

  // regulations must have one controlled event
  // regulations may have one controller
  // controller != controlled
  def checkRegulationArgs(m: Mention): Boolean = {
    (m.arguments.get("Controller"), m.arguments.get("Controlled")) match {
      case (_, None) => false
      case (None, Some(controlled)) => controlled.size == 1 && isBioEvent(controlled.head) // controlled must be a valid event...
      case (Some(controller), Some(controlled)) =>
        (controlled.size == 1
          && isBioEvent(controlled.head)
          && controller.size == 1
          && controller.head != controlled.head
          && !controller.head.label.endsWith("egulation")) // this last test is just a hack for the visualizer.
                                                           // The controller of a regulation can in fact be a regulation.
    }
  }

  // is the mention a biochemical event?
  def isBioEvent(m: Mention): Boolean = !m.isInstanceOf[TextBoundMention] && EventLabels.contains(m.label)
}

object BasicRuler {
  val resourcesDir = "/edu/arizona/sista/bionlp/extractors"
  val filesDir = new File(".", "src/main/resources/edu/arizona/sista/bionlp/extractors").getCanonicalPath()

  def readRules(shell: Boolean = false): String = readEntityRules(shell) + "\n\n" + readEventRules(shell)

  def readEntityRules(shell: Boolean = false): String = {
    val dir = if (shell) filesDir else resourcesDir
    val read = if (shell) readFile _ else readResource _
    val files = Seq(s"$dir/default_entities.yml", s"$dir/DARPA_entities.yml")
    files map read mkString "\n\n"
  }

  def readEventRules(shell: Boolean = false): String = {
    val dir = if (shell) filesDir else resourcesDir
    val read = if (shell) readFile _ else readResource _
    val files = Seq(s"$dir/phospho_events.yml",
      s"$dir/ubiq_events.yml",
      s"$dir/hydrox_events.yml",
      s"$dir/hydrolysis_events.yml",
      s"$dir/bind_events.yml",
      s"$dir/exchange_events.yml",
      s"$dir/degrad_events.yml",
      s"$dir/transcription_events.yml",
      s"$dir/regulation_events.yml",
      s"$dir/neg_reg_events.yml",
      s"$dir/pos_reg_events.yml",
      s"$dir/transport_events.yml")
    files map read mkString "\n\n"
  }

  def readResource(filename: String) = {
    val source = io.Source.fromURL(getClass.getResource(filename))
    val data = source.mkString
    source.close()
    data
  }

  def readFile(filename: String) = {
    val source = io.Source.fromFile(filename)
    val data = source.mkString
    source.close()
    data
  }
}
