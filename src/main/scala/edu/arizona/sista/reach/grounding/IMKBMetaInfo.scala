package edu.arizona.sista.reach.grounding

/**
  * Class representing meta-information about an external Knowledge Base.
  *   Written by: Tom Hicks. 10/25/2015.
  *   Last Modified: Minimize default arguments.
  */
class IMKBMetaInfo (

  /** The primary URI of the external KB (e.g., http://identifiers.org/uniprot/). */
  val baseURI: String = "",

  /** The Resource Identifier for the primary resource location for this KB (e.g., MIR:00100164).
    * NB: This is MIRIAM registration ID of the external knowledge base, NOT an entity ID. */
  val resourceId: String = ""

) extends KBMetaInfo {

  // save the constructor arguments in the parent map:
  put("baseURI", baseURI)
  put("resourceId", resourceId)

  /**
    * Using the given ID string, generate a URI which references an entry
    * in an external knowledge base:
    * (e.g., given "P2345" => "http://identifiers.org/uniprot/P2345").
    */
  def referenceURI (id:String): String = {
    val baseURI = super.getOrElse("baseURI", "")
    s"${baseURI}${id}"
  }

  override def toString(): String = {
    s"<IMKBMetaInfo: ${super.toString()}>"
  }

}
