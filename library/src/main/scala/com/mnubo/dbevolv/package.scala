package com.mnubo

package object dbevolv {
  /**
    * Replicate the try-with-resource of Java7 or C#.
    */
  def using[RESOURCE <: AutoCloseable, RES](resource: RESOURCE)(action: RESOURCE => RES) =
    try action(resource)
    finally resource.close()

}
