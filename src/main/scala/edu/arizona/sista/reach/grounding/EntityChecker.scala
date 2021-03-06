package edu.arizona.sista.reach.grounding

import java.io._

import org.biopax.paxtools.io._
import org.biopax.paxtools.model._
import org.biopax.paxtools.model.level3._

import scala.collection.JavaConverters._

import edu.arizona.sista.reach.grounding.ReachIMKBLookups._
import edu.arizona.sista.reach.grounding.ReachKBConstants._

/**
  * Program to lookup/check incoming BioPax model entities against local knowledge bases.
  *   Author: by Tom Hicks. 5/14/2015.
  *   Last Modified: Replace ChEBI and HMDB KBs with PubChem.
  */
object EntityChecker extends App {

  private val idCntr = new IncrementingCounter() // counter sequence class

  /** Search sequence for resolving proteins. */
  protected val proteinSearcher = Seq( staticProteinFamilyKBLookup,
                                       staticProteinFamily2KBLookup,
                                       staticProteinKBLookup )

  /** Search sequence for small molecules. */
  protected val chemSearcher = Seq( staticChemicalKBLookup )

  /** Search sequence for sub cellular locations terms. */
  protected val cellLocationSearcher = Seq( staticCellLocationKBLookup )


  /** Read the BioPAX model from the given input stream and check the entities. */
  def readAndCheckBioPax (fis:InputStream) = {
    val bpIOH:BioPAXIOHandler = new SimpleIOHandler()
    val model:Model = bpIOH.convertFromOWL(fis)
    checkProteins(model)
    checkCellLocations(model)
    checkChemicals(model)
  }


  private def checkChemicals (model:Model) = {
    val instances:collection.mutable.Set[SmallMolecule] =
      (model.getObjects(classOf[SmallMolecule])).asScala
    val molecules = instances.toSeq.map(_.getDisplayName()).sorted.distinct
    println(s"FOUND: ${molecules.size} small molecules in input model")
    val missing = molecules.filterNot(lookup(_, chemSearcher))
    outputMissing(missing, GendChemicalFilename, GendChemicalPrefix)
  }

  private def checkCellLocations (model:Model) = {
    val instances:collection.mutable.Set[CellularLocationVocabulary] =
      (model.getObjects(classOf[CellularLocationVocabulary])).asScala
    val cellLocs = instances.toSeq.flatMap(_.getTerm().asScala).sorted.distinct
    println(s"FOUND: ${cellLocs.size} cellular location terms in input model")
    val missing = cellLocs.filterNot(lookup(_, cellLocationSearcher))
    outputMissing(missing, GendCellLocationFilename, GendCellLocationPrefix)
  }

  private def checkProteins (model:Model) = {
    val instances:collection.mutable.Set[Protein] = (model.getObjects(classOf[Protein])).asScala
    var proteins = instances.toSeq.map(_.getDisplayName()) ++ findComplexProteinNames(model)
    proteins = proteins.sorted.distinct     // sort and remove duplicate names
    println(s"FOUND: ${proteins.size} distinct proteins in input model")
    val missing = proteins.filterNot(lookup(_, proteinSearcher))
    outputMissing(missing, GendProteinFilename, GendProteinPrefix)
  }


  /** Return a list of the (unsorted and non-unique) names of proteins contained in complexes. */
  private def findComplexProteinNames (model:Model): Seq[String] = {
    val complexes = model.getObjects(classOf[Complex]).asScala
    val proteins = complexes.map(_.getComponent().asScala.filter(_.isInstanceOf[Protein])).flatten
    val protNames = proteins.map(_.getDisplayName()).toSeq
    return protNames
  }


  /** Output the missing entity names and generated IDs to the given file. */
  private def outputMissing (missing:Seq[String], filename:String, prefix:String) = {
    val outFile:File = ReachKBUtils.makeFileInKBDir(filename)
    val out:PrintWriter = new PrintWriter(new BufferedWriter(new FileWriter(outFile)))
    // val now = Platform.currentTime.toString  // make ID unique per program run
    missing.foreach { entName =>
      val nid = "%s%05d".format(prefix, idCntr.next)
      out.println(s"${entName}\t${nid}")
    }
    out.flush()
    out.close()
  }


  /** Search the KB lookups in sequence for the given text string. Return true for
    * the first lookup which resolves the given text, or false if none do. */
  private def lookup (text: String, searchSequence:Seq[IMKBLookup]): Boolean = {
    searchSequence.foreach { kbLookup =>    // for each KB in the sequence
      val res = kbLookup.resolve(text)      // lookup the given text string
      if (res.isDefined)                    // if an entry for the text is found in a KB
        return true
    }
    return false                            // else signal failure to find text in any KB
  }


  //
  // Top-level Main of script:
  //
  val filepath:String = if (!args.isEmpty) args(0) else ""
  val fis = new FileInputStream(filepath)
  readAndCheckBioPax(fis)
}
