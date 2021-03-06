package ppl.dsl.forge
package examples
package optiml

import optila.OptiLADSL
import core.ForgeApplicationRunner

object OptiMLDSLRunner extends ForgeApplicationRunner with OptiMLDSL

trait OptiMLDSL extends OptiLADSL {
  override def dslName = "OptiML"
  
  override def specification() = {
    // include OptiLA
    super.specification()
    
    // optiml ops to come
  }  
}