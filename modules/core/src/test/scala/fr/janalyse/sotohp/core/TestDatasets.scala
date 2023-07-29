package fr.janalyse.sotohp.core

import fr.janalyse.sotohp.model.*
import java.util.UUID
import java.nio.file.Path

trait TestDatasets {
  val photoOwnerId = PhotoOwnerId(UUID.fromString("CAFECAFE-CAFE-CAFE-BABE-BABEBABE"))

  val dataset1         = Path.of("samples/dataset1")
  val dataset1Example1 = Path.of("samples/dataset1/example1.jpg")
  val dataset1Example2 = Path.of("samples/dataset1/example2.jpg")
  val dataset1Example3 = Path.of("samples/dataset1/example3.gif")
  val dataset1Example4 = Path.of("samples/dataset1/example4.tif")
  val dataset1Example5 = Path.of("samples/dataset1/example5.png")

  val dataset2           = Path.of("samples/dataset2")
  val dataset2tag1       = Path.of("samples/dataset2/tags/tag1.jpg")
  val dataset2landscape1 = Path.of("samples/dataset2/landscapes/landscape1.jpg")
}
