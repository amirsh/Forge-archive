package ppl.dsl.forge
package examples

import core.{ForgeApplication,ForgeApplicationRunner}

object OptiWranglerDSLRunner extends ForgeApplicationRunner with OptiWranglerDSL

trait Base extends ForgeApplication {
  def addErrorChecking() {
    val Error = grp("Error")

    direct (Error) ("goodbye", Nil, MString :: MUnit, effect = simple) implements codegen ($cala, ${
      //System.err.println("\n\n\t" + $0 + "\n\n")
      System.err.println("\t" + $0 + "")
      System.exit(-1)
    })
  }
    
  def addClock() {
    val Clock = grp("Clock")
  
    direct (Clock) ("clock", Nil, MUnit :: MInt, effect = simple) implements codegen ($cala, ${
      System.currentTimeMillis().toInt
    })
    direct (Clock) ("clock", Nil, MAny :: MInt, effect = simple) implements codegen ($cala, ${
      System.currentTimeMillis().toInt
    })
  }
}

trait OptiWranglerDSL extends Base {
  def dslName = "OptiWrangler"
  
  def specification() = {
    importScalaOps()
    addErrorChecking()
    addClock()
    //addWranglerOps()
    addTableOps()
  }

  def addTableOps() {
    val A = tpePar("A")
    val Table = tpe("Table")
    val SArray = tpeInst(MArray, MString)
    val SSArray = tpeInst(MArray, SArray) 
    val MSI = tpeInst(MMap, List(MString, MInt))
  
    data(Table, ("_data", SSArray), ("_length", MInt), ("_width", MInt), ("_header", MSI), ("_name", MString))

    // allocators - I think I'm going to move codegen to underneath spec
    
    val TableOps = withTpe (Table)
    TableOps {
      // getters and setters
      compiler ("data") (Nil :: SSArray) implements getter(0, "_data")
      compiler ("header") (Nil :: MSI) implements getter(0, "_header")
      infix ("length") (Nil :: MInt) implements getter(0, "_length")
      compiler ("width") (Nil :: MInt) implements getter(0, "_width")
      compiler ("name") (Nil :: MString) implements getter(0, "_name")
      compiler ("set_data") (SSArray :: MUnit, effect=write(0)) implements setter(0, "_data", quotedArg(1))
      compiler ("set_header") (MSI :: MUnit, effect=write(0)) implements setter(0, "_header", quotedArg(1))
      compiler ("set_length") (MInt :: MUnit, effect=write(0)) implements setter(0, "_length", quotedArg(1))
      compiler ("set_width") (MInt :: MUnit, effect=write(0)) implements setter(0, "_width", quotedArg(1))
      compiler ("set_name") (MString :: MUnit, effect=write(0)) implements setter(0, "_name", quotedArg(1))

      // parallelization - trying this style
      infix ("new_table") (MInt :: Table, effect=mutable) implements allocates (Table, 
        ${array_empty[ForgeArray[String]]($1)}, ${$1}, ${array_length(array_apply(data($0), unit(0)))}, 
          ${map_empty[String, Int]()}, ${name($0)}
      )
      // infix ("length") (Nil :: MInt) implements composite ${ length($self) }
      infix ("apply") (MInt :: SArray) implements composite ${ array_apply(data($self), $1) }
      infix ("update") ((MInt, SArray) :: MUnit, effect=write(0)) implements
        composite ${ array_update(data($self), $1, $2) } 

      // for PCB
      compiler ("realloc") (MInt :: MUnit, effect=write(0)) implements single ${
        val d = data($self)
        var n = Math.max(4, array_length(d)*2)
        while (n < $1) n = n*2
        val dnew = array_empty[ForgeArray[String]](n)
        array_copy(d, 0, dnew, 0, $self.length)
        set_data($self, d.unsafeImmutable)
      }
      compiler ("insertspace") ((MInt, MInt) :: MUnit, effect = write(0)) implements single ${
        val d = data($self)
        if(array_length(d) - $self.length < $2) realloc($self, $self.length + $2)
        array_copy(d, $1, d, $1 + $2, $self.length - $2)
        set_length($self, $self.length + $2)
      }
      infix ("append") ((MInt, SArray) :: MUnit, effect=write(0)) implements single ${
        insertspace($self, $1, 1)
        $self($1) = $2
      }
      compiler ("appendable") ((MInt, SArray) :: MBoolean) implements single("true")
      compiler ("copy") ((MInt, Table, MInt, MInt) :: MUnit, effect=write(2)) implements single ${
        array_copy(data($self), $1, data($2), $3, $4)
      }

      //parallelize as ParallelCollection(SArray, lookupOp("new_table"), lookupOp("length"), lookupOverloaded("apply", 0), lookupOp("update"))
      parallelize as ParallelCollectionBuffer(SArray, lookupOp("new_table"), lookupOp("length"), lookupOverloaded("apply", 0), lookupOp("update"), lookupOp("set_length"), lookupOp("appendable"), lookupOp("append"), lookupOp("copy"))

      // More bones
      infix ("apply") ((MInt, MInt) :: MString) implements composite ${
        array_apply(array_apply(data($self), $1), $2)
      } 

/*
      infix ("getHeaderIndex") (MString :: MInt) implements composite ${
        if(not(map_contains(header($self), $1))) goodbye("Requested a header which doesn't exist : " + $1)
        map_getOrElse[String, Int](header($self), $1, -1) match {
          case x: Int => unit(x)
          case x: Rep[Int] => x // yes, this matches on Rep[_]
        }
      }

      // as opposed to matching internally on MAny
      infix ("getColumn") (MInt :: MInt) implements composite ${$1}
      infix ("getColumn") (MString :: MInt) implements composite ${$self.getHeaderIndex($1)}
  
      // the biggest todo of them all : best path : "Column" type
      infix ("getColumns") (A :: MArray(MInt)) implements composite ${
        val mA = manifest[A]
        mA match {
          case Manifest.Int => 
        }
      }

      infix ("getColumns") (MAny :: MArray(MInt)) implements composite ${ 
        getColumnsBody($1, header($self))
      }
*/

      infix ("promote") (SArray :: Table) implements composite ${
        set_header($self, promoteBody($1, width($self)))
        $self
      }

      infix ("promote") (MInt :: Table) implements composite ${
        $self.promote($self(1))
        //$self.delete($1)
        $self
      }

      infix ("mapHelper") ((MString ==> MString, MArray(MInt), MArray(MInt)) :: Table) implements map((SArray, SArray), 0, ${ row => mapBody(row, $1, $2, $3)})

      infix ("map") ((MString ==> MString, MAny) :: Table) implements composite ${
        val _width = array_range(0, width($self))   
        val indices = $self.getColumns($2)
        $self.mapHelper($1, _width, indices)
      }

      // todo : implements flatMap
      infix ("flatMapHelper") ((MString ==> SArray, MArray(MInt), MArray(MInt)) :: Table) implements composite ${
        set_data($self, array_flatmap[ForgeArray[String], ForgeArray[String]](data($self), row =>
          flatMapBody(row, $1, $2, $3)
        ))
        $self
      }

      infix ("flatMap") ((MString ==> SArray, MAny) :: Table) implements composite ${
        val _width = array_range(0, width($self))
        val indices = $self.getColumns($2)
        $self.flatMapHelper($1, _width, indices)
      }

      infix ("filterHelper") ((MString ==> MBoolean, MArray(MInt), MArray(MInt)) :: Table) implements filter ((SArray, SArray), 0, ${row => filterBody(row, $1, $2, $3)}, ${e => e})

      infix ("filter") ((MString ==> MBoolean, MAny) :: Table) implements composite ${
        val _width = array_range(0, width($self))   
        val indices = $self.getColumns($2)
        $self.filterHelper($1, _width, indices)
      }

      // Meat
      infix ("cut") ((MInt, MAny) :: Table) implements composite ${
        $self.map(cell => {
          if ($1 < 0) goodbye("Trying to cut on bad index: " + $1)
          if ($1 >= cell.size) cell
          else cell.substring(0, $1) + cell.substring($1 + 1)//ow_int_plus($1, 1))
        }, $2)
      }
  
      infix ("cut") (MInt :: Table) implements composite ${
        $self.cut($1, array_range(0, width($self)))
      }

      infix ("cut") (MString :: Table) implements composite ${
        $self.cut($1, array_range(0, width($self)))
      }

      infix ("cut") ((MString, MAny) :: Table) implements composite ${
        $self.map(_.replaceFirst($1, ""), $2)
      }

      infix ("cut") ((MString ==> MString) :: Table) implements composite ${
        $self.cut($1, array_range(0, width($self)))
      }

      infix ("cut") ((MString ==> MString, MAny) :: Table) implements composite ${
        $self.map(cell => {
          //try
          val result = $1(cell)
          val index = cell.indexOf(result)
          if (index == -1) cell
          else cell.substring(0, index) + cell.substring(index + result.size)
        }, $2)
      }

      infix ("cutRight") (MString :: Table) implements composite ${
        $self.cutRight($1, array_range(0, width($self)))
      }

      infix ("cutRight") ((MString, MAny) :: Table) implements composite ${
        $self.map(cell => {
          val index = cell.lastIndexOf($1)
          if (index == -1) cell
          else cell.substring(0, index) + cell.substring(index + $1.size)
        }, $2)
      }

      infix ("cutAll") (MString :: Table) implements composite ${
        $self.cutAll($1, array_range(0, width($self)))
      }

      infix ("cutAll") ((MString, MAny) :: Table) implements composite ${
        $self.map(_.replaceAll($1, ""), $2)
      }

      // IO - user
      infix ("toFile") (MString :: MUnit, effect = simple) implements composite ${
        toFile($1, name($self), data($self), header($self))
      }
    }


    //////////////////////////// Codegen ///////////////////////////////

    direct (Table) ("parseFileName", Nil, MString :: MString) implements codegen ($cala, ${
      val fileName = $0
      val forge_extension = "forge"
      val dirName = if(fileName.contains("/")) {
        fileName.substring(0, fileName.lastIndexOf("/") + 1)
      } else { "./" }
      val shortName = if(fileName.contains("/")) {
        fileName.substring(fileName.lastIndexOf("/")+1)
      } else {
        fileName
      }
      if (shortName.size == 0) { println("Directories not currently supported") }
      if(shortName.contains(".")) {
        val extension = shortName.substring(shortName.lastIndexOf(".")+1)
        val newExtension =
          if (extension == forge_extension) forge_extension+"."+forge_extension // wtf
          else forge_extension
        //(dirName, shortName.substring(0, shortName.lastIndexOf(".")) + "." + newExtension)
        shortName.substring(0, shortName.lastIndexOf(".")) + "." + newExtension
      } else {
        //(dirName, shortName + "." + forge_extension)
        shortName + "." + forge_extension
      }
    })

    // some operand issues
    direct (Table) ("ow_int_plus", Nil, (MInt, MInt) :: MInt) implements codegen ($cala, ${
      $0 + $1
    })

    // Scala generation - easiest development path for the moment
    direct (Table) ("getColumnsBody", Nil, (MAny, MSI) :: MArray(MInt)) implements codegen ($cala, ${
      def getColumn(column: Any, header : scala.collection.mutable.HashMap[String, Int]): Int = column match {
        case x: Int => x // no warnings because I didn't pass width along :-/
        case x: String => header.getOrElse(x, -1)
      }
      
      $0 match {
        //case x: Seq[Any] => x.map(y => getColumn(y, $1)).toArray
        case x: Array[Any] => x.map(y => getColumn(y, $1)).toArray
        case _ => Array(getColumn($0, $1))
      }
    })
  
    // it doesn't matter how this is implemented
    direct (Table) ("promoteBody", Nil, (SArray, MInt) :: MSI) implements codegen ($cala, ${
      val x = $0.zip(0 until $1).toMap
      val y = scala.collection.mutable.HashMap[String, Int]() 
      x.foreach{case(h, index) => y.put(h, index)}
      y
    })

    direct (Table) ("mapBody", Nil, (SArray, MString ==> MString, MArray(MInt), MArray(MInt)) :: SArray) implements codegen ($cala, ${
      $0.zip($2).map{case(cell, index) => 
        if ($b[3].contains(index)) $b[1](cell)
        else cell
      }
    })

    direct (Table) ("flatMapBody", Nil, (SArray, MString ==> SArray, MArray(MInt), MArray(MInt)) :: SSArray) implements codegen ($cala, ${
      $0.zip($2).map{case(cell, index) => 
        if ($b[3].contains(index)) $b[1](cell)
        else Array(cell)
      }
    })

    direct (Table) ("filterBody", Nil, (SArray, MString ==> MBoolean, MArray(MInt), MArray(MInt)) :: MBoolean) implements codegen ($cala, ${
      val x = 3 // no op test
      $0.zip($2).map{case(cell, index) => 
        if ($b[3].contains(index)) $b[1](cell)
        else true
      }.reduce(_ || _)
    })

    // I/O
    static (Table) ("apply", Nil, (SSArray, MInt, MInt, MSI, MString) :: Table, effect = mutable) implements allocates(Table, ${$0}, ${$1}, ${$2}, ${$3}, ${$4})

    static (Table) ("apply", Nil, MString :: Table) implements composite ${
      val d = fromFile($0)
      Table(d, array_length(d), array_length(array_apply(d,0)), map_empty[String, Int](), parseFileName($0))
    }

    direct (Table) ("fromFile", Nil, MString :: SSArray) implements codegen ($cala, ${
      scala.io.Source.fromFile($0).getLines().map(_.split(",").toArray).toArray
    }) 

    // ignore header because
    direct (Table) ("toFile", Nil, (MString, MString, SSArray, MSI) :: MUnit, effect = simple) implements codegen ($cala, ${
      val of = new java.io.PrintStream(new java.io.FileOutputStream($0 + $1))
      of.println($2.map(x => x.mkString(",")).mkString("#"))
      of.close()
    })

    //()
  }
}