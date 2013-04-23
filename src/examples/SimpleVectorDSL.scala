package ppl.dsl.forge
package examples

import core.{ForgeApplication,ForgeApplicationRunner}

object SimpleVectorDSLRunner extends ForgeApplicationRunner with SimpleVectorDSL

trait SimpleVectorDSL extends ForgeApplication with ScalaOps {
  /**
   * The name of your DSL. This is the name that will be used in generated files,
   * package declarations, etc.
   */
  def dslName = "SimpleVector"
    
  /**
   * The specification is the DSL definition (types, data structures, ops, code generators)
   */
  def specification() = {
    /**
     * Include Scala ops
     */
     addScalaOps()
        
    /**
     * Types
     */
    val T = tpePar("T")
    val Vector = tpe("Vector", List(T)) 
    // val IntVector = tpeInst(Vector, List(MInt)) 
       
    tpeAlias("V", Vector)
    tpeAlias("VI", tpeInst(Vector, List(MInt)))    
    
    /**
     * Data structures
     */
    
    // TODO: generate Delite struct case classes instead of back-end data structures 
    // to remove effect errors
    data(Vector, List(T), ("_length", MInt), ("_data", DArray(T)))
    
    /* Generic formatting instance */
    val stream = ForgePrinter()
        
    /**
     * Ops
     * 
     * We could simplify this by reusing templates even more, i.e. specializing for different types
     * (e.g. accept a list of binary zip ops that only differentiate in function applied)
     */           
    // val vnew = op (Vector) ("apply", methodTpe(static), tpePars(T), args(MInt), retTpe(Vector), codegenerated, effect = mutable)
        
    val vnew = op (Vector) ("apply", static, List(T), List(MInt), Vector, codegenerated, effect = mutable)
    val vlength = op (Vector) ("length", infix, List(T), List(Vector), MInt, codegenerated)    
    val vapply = op (Vector) ("apply", infix, List(T), List(Vector,MInt), T, codegenerated)
    val vupdate = op (Vector) ("update", infix, List(T), List(Vector,MInt,T), MUnit, codegenerated, effect = write(0))
    
    val vtimesScalar = op (Vector) ("*", infix, List(T withBound TNumeric), List(Vector,T), Vector, map((T,T,Vector), 0, "e => e*"+quotedArg(1)))
    val vfoo = op (Vector) ("foo", infix, List(T), List(Vector,T), tpeInst(Vector,List(MDouble)), map((T,MDouble,Vector), 0, "e => unit(0.0)")) // problem: primitive lifting isn't in scope in the ops
     
    // uses a function arg inside a delite op
    val vbar = op (Vector) ("bar", infix, List(T), List(Vector,("f", MFunction(List(("x",T)),T))), Vector, map((T,T,Vector), 0, "e => "+quotedArg("f")+"(e)")) 

    // generic map, reduce functions for vector
    val mapper = MFunction(List(("x",T)),T)  // better syntax for functions would be nice
    val reducer = MFunction(List(("a",T),("b",T)),T)      
    val vmap = op (Vector) ("map", infix, List(T), List(Vector,mapper), Vector, map((T,T,Vector), 0, "e => "+quotedArg(1)+"(e)"))    
    val vreduce = op (Vector) ("reduce", infix, List(T withBound TNumeric), List(Vector,reducer), T, reduce((T,Vector), 0, lookup("Numeric","zero").get, "(a,b) => "+quotedArg(1)+"(a,b)"))
  
    val cond = MFunction(List(("x",T)),MBoolean)
    val vfilter = op (Vector) ("filter", infix, List(T), List(Vector,cond), Vector, filter((T,T,Vector), 0, "e => " + quotedArg(1) + "(e)", "e => e"))
    
    // a composite op
    val vmapreduce = op (Vector) ("mapreduce", infix, List(T withBound TNumeric), List(Vector,mapper,reducer), T, composite(Vector, {
      quotedArg(0)+".map("+quotedArg(1)+").reduce("+quotedArg(2)+")"
    }))
    
    val vbasic = op (Vector) ("basic", infix, List(T), List(Vector, ("y", MInt, "1"), ("z", MInt, "1")), MInt, codegenerated)
    codegen (vbasic) ($cala, quotedArg("y")+ "+3+" + quotedArg("z"))
 
    val vset = op (Vector) ("set", infix, List(T withBound TNumeric), List(Vector, ("x", MInt), ("z", MInt, "3")), tpeInst(Vector, List(MInt)), map ((T,MInt,Vector), 0, "y  => "+quotedArg("x") + " + " + quotedArg("z")))

    val vplus = op (Vector) ("+", infix, List(T withBound TNumeric), List(Vector,Vector), Vector, zip((T,T,T,Vector), (0,1), "(a,b) => a+b"))
    
    val vsum = op (Vector) ("sum", infix, List(T withBound TNumeric), List(Vector), T, reduce((T,Vector), 0, lookup("Numeric","zero").get, "(a,b) => a+b"))
    
    val vprint = op (Vector) ("pprint", infix, List(T), List(Vector), MUnit, foreach((T,Vector), 0, "a => println(a)"), effect = simple) // will print out of order in parallel, but hey
         
    val vslice = op (Vector) ("slice", infix, List(T), List(Vector, MInt, MInt), Vector, single(Vector, { 
      // inside single tasks we use normal DSL code just like applications would (modulo arg names)
      stream.printLines(
        "val st = " + quotedArg(1),
        "val en = " + quotedArg(2),
        "val out = Vector[T](en - st)",
        "var i = st",        
        "while (i < en) {",
        "  out(i-st) = "+quotedArg(0)+"(i)",
        "  i += 1",
        "}",
        "out"
      )}))        
                     
    // data manipulation
    
    // TODO: try to make SimpleVector use DeliteArrayBuffer instead of defining its own buffer methods
    // what should the dc methods do then? just call the underlying the _data methods..
    
    // vector_ methods are the Delite convention for methods only available inside the compiler (not part of the public interface)
    // TODO: these should be a different method type (i.e. "compiler"), so that the signatures are placed in isolated traits
    
    val vrawdata = op (Vector) ("vector_raw_data", direct, List(T), List(Vector), DArray(T), codegenerated)
    codegen (vrawdata) ($cala, quotedArg(0) + "._data")

    val vsetrawdata = op (Vector) ("vector_set_raw_data", direct, List(T), List(Vector, DArray(T)), MUnit, codegenerated, effect = write(0))
    codegen (vsetrawdata) ($cala, quotedArg(0) + "._data = " + quotedArg(1))
    
    op (Vector) ("insert", infix, List(T), List(Vector,MInt,T), MUnit, single(MUnit, {
      stream.printLines(
        "vector_insertspace("+quotedArg(0)+","+quotedArg(1)+",1)",
        quotedArg(0)+"("+quotedArg(1)+") = " + quotedArg(2)
    )}), effect = write(0))

    val vappend = op (Vector) ("append", infix, List(T), List(Vector,MInt,T), MUnit, single(MUnit, {
      quotedArg(0)+".insert("+quotedArg(0)+".length, "+quotedArg(2)+")"
    }), effect = write(0))
    
    op (Vector) ("vector_insertspace", direct, List(T), List(Vector,MInt,MInt), MUnit, single(MUnit, {
      val v = quotedArg(0)
      val pos = quotedArg(1)
      val len = quotedArg(2)
      // can clean this up using string interpolation.. but how does that interact with stream.printLines? can we get rid of stream.printLines?
      stream.printLines(        
        "vector_ensureextra("+v+","+len+")",
        "val data = vector_raw_data("+v+")",
        "darray_unsafe_copy(data, "+pos+", data, "+pos+" + "+len+", "+v+".length - "+pos+")",
        "vector_set_size("+v+", "+v+".length + "+len+")"
    )}), effect = write(0))
    
    op (Vector) ("vector_ensureextra", direct, List(T), List(Vector,MInt), MUnit, single(MUnit, {
      val v = quotedArg(0)
      val extra = quotedArg(1)
      stream.printLines(        
        "val data = vector_raw_data("+v+")",
        "if (data.length - "+v+".length < "+extra+") {",
        "  vector_realloc("+v+", "+v+".length + "+extra+")",
        "}"
    )}), effect = write(0))        
    
    op (Vector) ("vector_realloc", direct, List(T), List(Vector,MInt), MUnit, single(MUnit, {
      val v = quotedArg(0)
      val minLen = quotedArg(1)
      stream.printLines(        
        "val data = vector_raw_data("+v+")",
        "var n = Math.max(4, data.length * 2)",
        "while (n < "+minLen+") n = n*2",
        "val d = darray_new[T](n)",
        "darray_unsafe_copy(data, 0, d, 0, "+v+".length)",        
        "vector_set_raw_data("+v+", d.unsafeImmutable)"    
    )}), effect = write(0))        
            
              
    /**
     * Code generators
     */

    // TODO: how do we refer to other methods or codegenerators inside a particular codegen impl? e.g. vfoo uses vlength   
    codegen (vnew) ($cala, "new "+vnew.tpeName+"["+vnew.tpeInstance(0)+"]("+quotedArg(0)+", new Array["+vnew.tpeInstance(0)+"]("+quotedArg(0)+"))")
    codegen (vlength) ($cala, quotedArg(0) + "._length")
    codegen (vapply) ($cala, quotedArg(0) + "._data.apply(" + quotedArg(1) + ")")
    codegen (vupdate) ($cala, quotedArg(0) + "._data.update(" + quotedArg(1) + ", " + quotedArg(2) + ")")        
    
    /**
     * DeliteCollectionification
     * This enables a tpe to be passed in as the collection type of a Delite op
     */
    
    // Vector is DeliteCollection(T, vnew, vlength, vapply, vupdate)
    
    // what is this going to do in the library? should we have a different way of passing the parallelization strategy in 
    // the DeliteCollectionBuffer that does not require an op?
    // val vparallelization = op (Vector) ("vector_parallelization", direct, List(T), List(Vector,MBoolean), DeliteParallelStrategy, composite(DeliteParallelStrategy, {
    //     "if (" + quotedArg(1) + ") " + quote(parBuffer) + " else " + quote(parFlat)
    // }))
    
    val vsetsize = op (Vector) ("vector_set_size", direct, List(T), List(Vector,MInt), MUnit, codegenerated, effect = write(0))
    codegen (vsetsize) ($cala, quotedArg(0) + "._length = " + quotedArg(1))
    
    // TODO: composite ops need to be put in impl files for correct scoping of front-end traits (especially problematic in the lib implementation because of the recursive loop)
  
    val vappendable = op (Vector) ("vector_appendable", direct, List(T), List(Vector,MInt,T), MBoolean, single(MBoolean, {
      "unit(true)" // TODO: unit should not be necessary (boolean lift not mixed in yet)
    }))

    val vcopy = op (Vector) ("vector_copy", direct, List(T), List(Vector,MInt,Vector,MInt,MInt), MUnit, single(MUnit, {
      val src = "vector_raw_data(" + quotedArg(0) + ")"
      val dest = "vector_raw_data(" + quotedArg(2) + ")"
      "darray_unsafe_copy("+src+","+quotedArg(1)+","+dest+","+quotedArg(3)+","+quotedArg(4)+")"
    }), effect = write(2))
    
    Vector is DeliteCollectionBuffer(T, vnew, vlength, vapply, vupdate, /*vparallelization,*/ vsetsize, vappendable, vappend, vcopy)
    
    ()    
  }
}
 
