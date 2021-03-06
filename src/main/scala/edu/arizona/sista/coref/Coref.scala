package edu.arizona.sista.coref

import edu.arizona.sista.odin.{Mention, _}
import edu.arizona.sista.processors.Document
import edu.arizona.sista.reach.grounding.{ReachKBConstants, KBEntry}
import edu.arizona.sista.reach.{mentions, DarpaActions, DarpaLinks}
import edu.arizona.sista.reach.utils.DependencyUtils._
import edu.arizona.sista.reach.display._
import edu.arizona.sista.reach.mentions._
import edu.arizona.sista.coref.CorefUtils._

import scala.annotation.tailrec

class Coref {

  val debug: Boolean = false
  val verbose: Boolean = debug
  val da: DarpaActions = new DarpaActions()

  /**
    * Make a map from TextBoundMentions to copies of themselves with a maximum of 1 antecedent
    */
  def resolveTBMs(mentions: Seq[CorefTextBoundMention]): Map[CorefTextBoundMention, Seq[CorefTextBoundMention]] = {
    mentions.map(mention => (mention, mention.toSingletons)).toMap
  }

  /**
    * Make a map from TextBoundMentions to the sieves used to find their antecedents (if any)
    */
  def tbmSieves(mentions: Seq[CorefTextBoundMention]): Map[CorefTextBoundMention, Set[String]] = {
    mentions.map(mention => (mention, mention.sieves)).toMap
  }

  /**
    * Make a map from the given SimpleEvent mentions to the same mentions but with any generic mentions (including
    * in the arguments) replaced with their non-generic antecedents.
    */
  def resolveSimpleEvents(evts: Seq[CorefEventMention],
                          resolvedTBMs: Map[CorefTextBoundMention, Seq[CorefMention]],
                          sieveMap: Map[CorefTextBoundMention, Set[String]]): Map[CorefEventMention, Seq[CorefEventMention]] = {
    require(evts.forall(_.matches("SimpleEvent")), s"Only simple events should be passed to the first argument of" +
      s" resolveSimpleEvents. you passed ${evts.filterNot(_.matches("SimpleEvent")).map(_.text).mkString("\n", "\n", "\n")}")

    // Events which are themselves generic trigger a search for specific events of the same type
    val (generics, specifics) = evts.partition(m => m.isGeneric)

    val (toInspect, solid) = specifics.partition(m => genericInside(m))

    // EventMentions with no generic participants point to themselves
    val solidMap = (for {
      s <- solid
    } yield {
      s -> Seq(s)
    }).toMap

    val inspectedMap = (for {
      specific <- toInspect

      // Search for arguments in already-completed TBM map
      resolvedArgs = for {
        (lbl, arg) <- specific.arguments
        argMs = arg.map(m => {
          specific.sieves ++= sieveMap.getOrElse(m.asInstanceOf[CorefTextBoundMention], Set.empty)
          resolvedTBMs.getOrElse(m.asInstanceOf[CorefTextBoundMention], Nil)
        })
      } yield lbl -> argMs

      // Because of plural anaphors like "them", we may have to split the arguments into multiple sets to make
      // new events
      argSets = specific match {
        // binding is a special case because it requires exactly two themes, while others require exactly one
        case binding if binding matches "Binding" => {
          val exceptTheme = combineArgs((resolvedArgs - "theme" - "theme1" - "theme2")
            .map(entry => entry._1 -> entry._2.flatten))
          val themeSets = combination(resolvedArgs.getOrElse("theme", Nil) ++
            resolvedArgs.getOrElse("theme1", Nil) ++
            resolvedArgs.getOrElse("theme2", Nil), 2)
          val newSets = exceptTheme.flatMap(argMap => themeSets.map(themeSet =>
            argMap + ("theme" -> corefDistinct(Seq(themeSet.headOption, themeSet.lastOption).flatten))))
          newSets
        }
        case _ => combineArgs(resolvedArgs.map(entry => entry._1 -> entry._2.flatten))
      }
    } yield {
      if (verbose && argSets.nonEmpty) {
        argSets.foreach { argSet =>
          println("argSet: ")
          argSet.foreach {
            case (lbl: String, ms: Seq[CorefMention]) =>
              println(lbl + " -> " + ms.map(m => m.text + m.antecedents.map(_.text).mkString("[", ",", "]")).mkString(","))
          }
        }
      }
      // for each complete set of arguments, we create a new event, being careful to copy over modifications
      val value = argSets.flatMap(argSet =>
        if (argsComplete(argSet, specific.labels)) {
          val generated = new CorefEventMention(
            specific.labels,
            specific.trigger,
            argSet,
            specific.sentence,
            specific.document,
            specific.keep,
            specific.foundBy + (if (specific.sieves.isEmpty) "" else specific.sieves.mkString(", ", ", ", "")))
          BioMention.copyAttachments(specific, generated)
          Seq(generated)
        } else Nil
      )
      specific -> value
    }).toMap

    val specificMap = solidMap ++ inspectedMap

    // Generic event mentions ("This phosphorylation promotes...") trigger a search for an appropriate specific event.
    val genericMap = generics.map(generic => (generic, generic.toSingletons)).toMap

    specificMap ++ genericMap
  }

  /**
    * Make a map from EventMentions to the sieves used to find their antecedents (if any)
    */
  def evtSieves(mentions: Seq[CorefEventMention]): Map[CorefEventMention, Set[String]] = {
    mentions.map(mention => (mention, mention.sieves)).toMap
  }

  // http://oldfashionedsoftware.com/2009/07/30/lots-and-lots-of-foldleft-examples/
  def group[A](list: List[A], size: Int): List[List[A]] =
    list.foldLeft((List[List[A]](), 0)) { (r, c) =>
      r match {
        case (head :: tail, num) =>
          if (num < size) ((c :: head) :: tail, num + 1)
          else (List(c) :: head :: tail, 1)
        case (Nil, num) => (List(List(c)), 1)
      }
    }._1.foldLeft(List[List[A]]())((r, c) => c.reverse :: r)

  /**
    * Similar to Seq's combination, but combining sequences of sequences, with the inner sequences being of size size
    */
  def combination(ms: Seq[Seq[CorefMention]], size: Int): Seq[Seq[CorefMention]] = {
    require(size > 0)
    val unique = corefDistinct(ms.flatten)
    if (unique.length <= size) return Seq(unique)

    ms.foldLeft[Seq[Seq[CorefMention]]](Nil)((args, thm) => args ++ (for {
      ant <- thm
      nxt <- if (size - 1 > 0) group(ms.span(ms.indexOf(_) <= ms.indexOf(thm))._2.flatten.toList, size - 1).toSeq else Seq(Nil)
    } yield Seq(Seq(ant), nxt.toSeq).flatten))
  }

  /**
    * If we have two controllers and one controlled, we want two sets, each with one controller and one controlled;
    * this function handles the general case
    */
  def combineArgs(argRaw: Map[String, Seq[CorefMention]], numThemes: Int = 1): Seq[Map[String, Seq[CorefMention]]] = {
    val args = argRaw.filterKeys(k => argRaw(k).nonEmpty)
    val stableKeys = args.keys.toSeq

    // General-case tail-recursive sum of the Int elements of a Seq
    def sum(xs: Seq[Int]): Int = {
      @tailrec
      def inner(xs: List[Int], accum: Int): Int = {
        xs match {
          case x :: tail => inner(tail, accum + x)
          case Nil => accum
        }
      }
      inner(xs.toList, 0)
    }

    // Counting down the combinations by toRemove units
    def oneLess(countdown: Seq[Int], toRemove: Int): Seq[Int] = {
      var i = countdown.length - 1
      while (i >= 0) {
        if (countdown(i) - toRemove >= 0) {
          return countdown.patch(i, Seq(countdown(i) - toRemove), 1)
        }
        i -= 1
      }
      Seq(-1)
    }

    // Tail-recursively create a Map from String to Seq[CorefMention] for a single combination of arguments, appending
    // it to the remainder
    def oneIteration(iteration: Seq[Int], sofar: Seq[Map[String, Seq[CorefMention]]], numThemes: Int): Seq[Map[String, Seq[CorefMention]]] = {
      iteration match {
        case end if sum(iteration) < 0 => sofar
        case _ => {
          oneIteration(
            oneLess(iteration, numThemes),
            sofar :+
              iteration.zipWithIndex.map(arg => stableKeys(arg._2) -> Seq(args(stableKeys(arg._2))(arg._1))).toMap,
            numThemes
          )
        }
      }
    }
    oneIteration(stableKeys.map(args(_).length - 1), Nil, numThemes)
  }

  /**
    * Make a map from the given ComplexEvent mentions (whether they are RelationMentions or EventMentions) to the same
    * mentions but with any generic mentions (including in the arguments) replaced with their non-generic antecedents.
    */
  def resolveComplexEvents(
    evts: Seq[CorefMention],
    resolved: Map[CorefMention, Seq[CorefMention]],
    sieveMap: Map[CorefMention, Set[String]]
  ): Map[CorefMention, Seq[CorefMention]] = {
    require(evts.forall(_.matches("ComplexEvent")), s"Only complex events should be passed to the first argument of" +
      s" resolveComplexEvents, but you passed ${evts.filterNot(_.matches("ComplexEvent")).map(_.text).mkString("\n", "\n", "\n")}")

    var createdComplexes: Seq[CorefMention] = Nil
    val (toInspect, solid) = evts.partition(m => genericInside(m))

    // Events with no generic participants point to themselves
    val solidMap = (for {
      s <- solid
    } yield {
      s -> Seq(s)
    }).toMap

    val inspectedMap = (for {
      evt <- toInspect

      // Check already-made maps for previously resolved arguments
      resolvedArgs = (for {
        (lbl, arg) <- evt.arguments
        //_=println(s"lbl: $lbl\nargs: ${arg.map(_.text).mkString("\n")}")
        argMs = arg.map(m => {
          evt.sieves ++= sieveMap.getOrElse(m.toCorefMention, Set.empty)
          resolved.getOrElse(m.toCorefMention, Nil)
        })
        argsAsEntities = argMs.map(ms => ms.map(m =>
          if (lbl == "controller" && m.isInstanceOf[EventMention] && m.isGeneric) {
            val ant = da.convertEventToEntity(m.antecedent.get.asInstanceOf[BioEventMention]).get.toCorefMention
            createdComplexes = createdComplexes :+ ant
            val copy = new CorefEventMention(
              m.labels,
              m.asInstanceOf[CorefEventMention].trigger,
              m.asInstanceOf[CorefEventMention].arguments,
              m.sentence,
              m.document,
              m.keep,
              m.foundBy)
            copy.antecedents = Set(ant)
            copy.sieves = m.sieves
            copy
          } else m))
      } yield lbl -> argsAsEntities.flatten).toMap

      // Because of plural anaphors like "them", we may have to split the arguments into multiple sets to make
      // new events
      argSets = combineArgs(resolvedArgs)
    } yield {
      if (verbose) {
        println("argSets:")
        argSets.foreach { argSet =>
          argSet.foreach {
            case (lbl: String, ms: Seq[CorefMention]) =>
              println(lbl + " -> " + ms.map(m => m.text + m.antecedents.map(_.text).mkString("[", ",", "]")).mkString(","))
          }
        }
      }

      // for each complete set of arguments, we create a new event, being careful to copy over modifications
      val value = argSets.flatMap(argSet =>
        if (argsComplete(argSet, evt.labels)) {
          evt match {
            case rel: CorefRelationMention =>
              val generated = new CorefRelationMention(
                evt.labels,
                argSet,
                evt.sentence,
                evt.document,
                evt.keep,
                evt.foundBy + (if (evt.sieves.isEmpty) "" else evt.sieves.mkString(", ", ", ", "")))
              BioMention.copyAttachments(evt, generated)
              Seq(generated)
            case evm: CorefEventMention =>
              val generated = new CorefEventMention(
                evt.labels,
                evt.asInstanceOf[CorefEventMention].trigger,
                argSet,
                evt.sentence,
                evt.document,
                evt.keep,
                evt.foundBy + (if (evt.sieves.isEmpty) "" else evt.sieves.mkString(", ", ", ", "")))
              BioMention.copyAttachments(evt, generated)
              Seq(generated)
          }
        }
        else Nil
      )
      evt -> value
    }).toMap

    // Note: we intentionally avoid generic complex events; recursive complex events are rare in any case, and
    // complicated enough to be likely to induce errors
    solidMap ++ inspectedMap ++ createdComplexes.map(c => c -> Seq(c)).toMap
  }

  /**
    * Using subfunctions for TextBoundMentions, SimpleEvents, and ComplexEvents, create maps for each mention in
    * mentions to its antecedent as determined by the linking functions already applied, creating new mentions as
    * necessary
    *
    * @param mentions all the input mentions with their antecedents already chosen by the linking functions
    * @return mentions with generic mentions replaced by their antecedents
    */
  def resolve(mentions: Seq[CorefMention]): Seq[CorefMention] = {
    if (verbose) println("\n=====Resolution=====\n")
    val tbms = mentions.filter(_.isInstanceOf[CorefTextBoundMention]).map(_.asInstanceOf[CorefTextBoundMention])
    val sevts = mentions.filter(m => m.isInstanceOf[CorefEventMention] && m.matches("SimpleEvent")).map(_.asInstanceOf[CorefEventMention])
    val cevts = mentions.filter(m => m.matches("ComplexEvent"))

    val resolvedTBMs = resolveTBMs(tbms)
    val tbmSieveMap = tbmSieves(tbms.filter(_.isGeneric))
    if (verbose) resolvedTBMs.foreach { case (k, v) => println(s"TBM: ${k.text} => (" + v.map(vcopy => vcopy.text + vcopy.antecedents.map(_.text).mkString("[", ",", "]")).mkString(",") + ")") }

    val resolvedSimple = resolveSimpleEvents(sevts, resolvedTBMs, tbmSieveMap)
    val evtSieveMap = evtSieves(sevts.filter(_.isGeneric))
    if (verbose) resolvedSimple.foreach { case (k, v) => println(s"SimpleEvent: ${k.text} => (" + v.map(vcopy => vcopy.text + vcopy.antecedents.map(_.text).mkString("[", ",", "]")).mkString(",") + ")") }

    val resolvedComplex = resolveComplexEvents(cevts, resolvedTBMs ++ resolvedSimple, tbmSieveMap ++ evtSieveMap)
    if (verbose) resolvedComplex.foreach { case (k, v) => println(s"ComplexEvent: ${k.text} => (" + v.map(vcopy => vcopy.text + vcopy.antecedents.map(_.text).mkString("[", ",", "]")).mkString(",") + ")") }
    val resolved = resolvedTBMs ++ resolvedSimple ++ resolvedComplex

    val retVal = corefDistinct(mentions.flatMap(mention => resolved.getOrElse(mention, Nil)))
    retVal
  }


  def apply(docMentions: Seq[Seq[Mention]]): Seq[Seq[CorefMention]] = {

    val aliases = scala.collection.mutable.HashMap.empty[KBEntry, BioMention]

    for {
      mentions <- docMentions
    } yield {

      if (mentions.isEmpty) Nil
      else {
        if (debug) {
          println("BEFORE COREF")
          displayMentions(mentions, mentions.head.document)
          println("Starting coref...")
        }

        // order mentions and also remove Generic_event mentions that do not have definite determiners.
        val legalDeterminers = Seq("the", "this", "that", "these", "those", "such")
        val orderedMentions: Seq[CorefMention] = mentions
          .map(_.toCorefMention)
          .filterNot(m => {
            m.isGeneric && !m.isClosedClass && {
              val sent = m.sentenceObj
              val hd = findHeadStrict(m.tokenInterval, sent)
              if (hd.isEmpty || hd.get < 0 || hd.get >= sent.dependencies.get.outgoingEdges.length) false
              else {
                try {
                  val outgoing = sent.dependencies.get.getOutgoingEdges(findHeadStrict(m.tokenInterval, sent).get)
                  val hasDet = outgoing.find(dep => dep._2 == "det" && legalDeterminers.contains(sent.words(dep._1).toLowerCase))
                  hasDet.isEmpty
                } catch {
                  case e: Throwable =>
                    println(s"Sentence: ${sent.getSentenceText()}")
                    println(s"Mention text: ${m.text}")
                    println(s"Head index is: $hd")
                    displayMention(m)
                    true
                }
              }
            }
          }).sorted[Mention]

        // find aliases
        val aliasRelations = orderedMentions filter (_ matches "Alias")
        for {
          aliasRelation <- aliasRelations
          entities = aliasRelation.arguments.getOrElse("alias", Nil)
          pair <- entities.combinations(2)
          (a, b) = (pair.head.toCorefMention, pair.last.toCorefMention)
          if compatibleGrounding(a, b) && // includes check to see that they're both grounded
            !(aliases contains a.grounding.get.entry) && // assume any existing entry is correct
            !(aliases contains b.grounding.get.entry) // so don't replace existing entry
        } {
          if (a.grounding.get.namespace == ReachKBConstants.DefaultNamespace) aliases(a.grounding.get.entry) = b
          else if (b.grounding.get.namespace == ReachKBConstants.DefaultNamespace) aliases(b.grounding.get.entry) = a
        }

        if (debug) println("\n=====Alias matching=====")
        // share more complete grounding based on alias map
        orderedMentions.filter(_.isInstanceOf[TextBoundMention]).foreach {
          mention =>
            val kbEntry = mention.grounding.get.entry
            if (aliases contains kbEntry) {
              if (debug) println(s"${mention.text} matches ${aliases(kbEntry).text}")
              mention.copyGroundingFrom(aliases(kbEntry))
              mention.sieves += "aliasGroundingMatch"
            }
        }

        val links = new DarpaLinks

        val allLinks = CorefFlow(links.exactStringMatch) andThen
          CorefFlow(links.groundingMatch) andThen
          CorefFlow(links.mutantProteinMatch) andThen
          CorefFlow(links.strictHeadMatch) andThen
          CorefFlow(links.pronominalMatch) andThen
          CorefFlow(links.nounPhraseMatch) andThen
          CorefFlow(links.simpleEventMatch)

        resolve(allLinks(orderedMentions, new LinearSelector)).filter(_.isComplete)
      }
    }
  }
}