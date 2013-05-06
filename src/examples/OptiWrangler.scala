package ppl.dsl.forge
package examples

import core.{ForgeApplication,ForgeApplicationRunner}

object OptiWranglerDSLRunner extends ForgeApplicationRunner with OptiWranglerDSL

trait OptiWranglerDSL extends ForgeApplication with ScalaOps {
  /**
   * The name of your DSL. This is the name that will be used in generated files,
   * package declarations, etc.
   */
  def dslName = "OptiWrangler"
  
  /**
   * The specification is the DSL definition (types, data structures, ops)
   */
  def specification() = {
    /**
     * Include Scala ops
     */
    addScalaOps() 
    
    /**
     * The main portion of our DSL
     */        
    addWranglerOps()
  }
  
  
  def addWranglerOps() {            
    // generic type parameters we will use 
     val T = tpePar("T") 
    // val R = tpePar("R")
    
    val Table = tpe("Table", T) 
    //val SArray = tpeInst(MArray, MString)

    val SArray = tpeInst(MArray, MString)
    val SSArray = tpeInst(MArray, SArray)

    val TableOps = withTpe (Table)
    TableOps {                  
      // data fields     
      data(("_data", SSArray), ("_width", MInt) /*, ("_header", Map[String, Int])*/, ("_name", MString))

      // allocation
      // todo test wrong experiment wip dev how to have defaults
      op (Table) ("apply", static, T, (MInt, MString), Table, effect = mutable) implements allocates(Table, ${null}, ${$0}, ${$1})
      
      // getters and setters
      "data" is (compiler, Nil, SSArray) implements getter(0, "_data")
      "length" is (infix, Nil, MInt) implements composite ${array_length(data($self))}
      
      // data ops             
      "apply" is (infix, (MInt), SArray) implements composite ${ array_apply(data($self), $1) }                        
      // example named arg
      "update" is (infix, (("i",MInt),("e",SArray)), MUnit, effect = write(0)) implements composite ${
        array_update(data($self), $i, $e)
      }

      parallelize as ParallelCollection(SArray,
       lookupOverloaded("apply",1),
       lookup("length"),
       lookupOverloaded("apply",0),
       lookup("update") 
      )            

      /* OptiWrangler Ops */
      "copy" is (compiler, (SSArray), Table) implements setter(0, "_data", quotedArg(1)) 

  //"mapWrap" is (compiler, (MString ==> MString, MInt), SSArray) implements map((SArray, SArray), 0, 
  //${ e => $1(array_apply(data($self), $2)) }
/*
      "mapWrap" is (compiler, (MString, MInt), SSArray) implements map((SArray, SArray), 0, 
        ${ e => $e }
      )
*/      
/*
      "map" is (compiler, (MString ==> MString, MInt), Table) implements composite ${copy(mapWrap($0, $1, $2))}
*/
      //"cut" is (infix, (MString, MInt), Table) implements composite ${ map(_.replaceFirst($1, $2)) }

      "potato" is (compiler, (tpe("ForgeArray", T)), MUnit) implements single ${}
    }                    
                                        
    ()    
  }
}
 