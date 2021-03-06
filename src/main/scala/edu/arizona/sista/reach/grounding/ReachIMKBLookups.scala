package edu.arizona.sista.reach.grounding

import edu.arizona.sista.reach.grounding.ReachKBConstants._

/**
  * Object which implements all Reach KB Lookup instances.
  *   Written by: Tom Hicks. 10/23/2015.
  *   Last Modified: Update for context tissue types KB.
  */
object ReachIMKBLookups {

  /** Single factory instance to generate Tsv IMKB classes. */
  val tsvIMKBFactory = new TsvIMKBFactory


  /** KB lookup to resolve subcellular location names via static KB. */
  def staticCellLocationKBLookup: IMKBLookup = {
    val metaInfo = new IMKBMetaInfo("http://identifiers.org/go/", "MIR:00000022")
    metaInfo.put("file", StaticCellLocationFilename)
    new IMKBLookup(tsvIMKBFactory.make("go", StaticCellLocationFilename, metaInfo))
  }

  /** KB lookup to resolve small molecule (metabolite) names via static KB. */
  def staticMetaboliteKBLookup: IMKBLookup = {
    val metaInfo = new IMKBMetaInfo("http://identifiers.org/hmdb/", "MIR:00000051")
    metaInfo.put("file", StaticMetaboliteFilename)
    new IMKBLookup(tsvIMKBFactory.make("hmbd", StaticMetaboliteFilename, metaInfo))
  }

  /** KB lookup to resolve small molecule (chemical) names via static KB. */
  def staticChemicalKBLookup: IMKBLookup = {
    val metaInfo = new IMKBMetaInfo("http://identifiers.org/pubchem.compound/", "MIR:00000034")
    metaInfo.put("file", StaticChemicalFilename)
    new IMKBLookup(tsvIMKBFactory.make("PubChem", StaticChemicalFilename, metaInfo))
  }

  /** KB lookup to resolve small molecule (chemical) names via static KB. */
  // def staticChemicalKBLookup: IMKBLookup = {
  //   val metaInfo = new IMKBMetaInfo("http://identifiers.org/chebi/", "MIR:00100009")
  //   metaInfo.put("file", StaticChemicalFilename)
  //   new IMKBLookup(tsvIMKBFactory.make("chebi", StaticChemicalFilename, metaInfo))
  // }

  /** KB accessor to resolve protein names via static KBs with alternate lookups. */
  def staticProteinKBLookup: IMKBProteinLookup = {
    val metaInfo = new IMKBMetaInfo("http://identifiers.org/uniprot/", "MIR:00100164")
    metaInfo.put("file", StaticProteinFilename)
    metaInfo.put("protein", "true")         // mark as from a protein KB
    new IMKBProteinLookup(tsvIMKBFactory.make("uniprot", StaticProteinFilename, true, metaInfo))
  }

  /** KB lookup to resolve protein family names via static KBs with alternate lookups. */
  def staticProteinFamilyKBLookup: IMKBFamilyLookup = {
    val metaInfo = new IMKBMetaInfo("http://identifiers.org/pfam/", "MIR:00000028")
    metaInfo.put("file", StaticProteinFamilyFilename)
    metaInfo.put("family", "true")          // mark as from a protein family KB
    new IMKBFamilyLookup(tsvIMKBFactory.make("pfam", StaticProteinFamilyFilename, metaInfo))
  }

  /** KB lookup to resolve protein family names via static KBs with alternate lookups. */
  def staticProteinFamily2KBLookup: IMKBFamilyLookup = {
    val metaInfo = new IMKBMetaInfo("http://identifiers.org/interpro/", "MIR:00000011")
    metaInfo.put("file", StaticProteinFamily2Filename)
    metaInfo.put("family", "true")          // mark as from a protein family KB
    new IMKBFamilyLookup(tsvIMKBFactory.make("interpro", StaticProteinFamily2Filename, true, metaInfo))
  }

}
