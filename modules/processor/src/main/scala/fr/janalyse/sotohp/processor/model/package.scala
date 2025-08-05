package fr.janalyse.sotohp.processor

import java.io.File
import java.nio.file.Path
import wvlet.airframe.ulid.ULID

package object model {

  // -------------------------------------------------------------------------------------------------------------------
  opaque type FaceId = ULID

  object FaceId {
    def apply(value: ULID): FaceId = value

    extension (faceId: FaceId) {
      def code: ULID       = faceId
      def asString: String = faceId.toString
    }
  }

  // -------------------------------------------------------------------------------------------------------------------
  opaque type NormalizedPath = Path
  object NormalizedPath {
    def apply(path: Path): NormalizedPath = path

    extension (path: NormalizedPath) {
      def parent: Path      = path.getParent
      def file: File        = path.toFile
      def path: Path        = path
      def fileName: String  = path.getFileName.toString
      def extension: String = path.getFileName.toString.split("\\.").last
    }
  }

  // -------------------------------------------------------------------------------------------------------------------
  opaque type DetectedFacePath = Path
  object DetectedFacePath {
    def apply(path: Path): DetectedFacePath = path

    extension (path: DetectedFacePath) {
      def parent: Path      = path.getParent
      def file: File        = path.toFile
      def path: Path        = path
      def fileName: String  = path.getFileName.toString
      def extension: String = path.getFileName.toString.split("\\.").last
    }
  }

  // -------------------------------------------------------------------------------------------------------------------
  opaque type XAxis = Double

  object XAxis {
    def apply(value: Double): XAxis = value

    extension (x: XAxis) {
      def value: Double = x
    }
  }

  // -------------------------------------------------------------------------------------------------------------------
  opaque type YAxis = Double

  object YAxis {
    def apply(value: Double): YAxis = value

    extension (y: YAxis) {
      def value: Double = y
    }
  }

  // -------------------------------------------------------------------------------------------------------------------
  opaque type BoxWidth = Double

  object BoxWidth {
    def apply(width: Double): BoxWidth = width

    extension (width: BoxWidth) {
      def value: Double = width
    }
  }

  // -------------------------------------------------------------------------------------------------------------------
  opaque type BoxHeight = Double

  object BoxHeight {
    def apply(height: Double): BoxHeight = height

    extension (height: BoxHeight) {
      def value: Double = height
    }
  }
  // -------------------------------------------------------------------------------------------------------------------
  
}
