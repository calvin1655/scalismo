/*
 * Copyright 2015 University of Basel, Graphics and Vision Research Group
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package scalismo.mesh

import scalismo.geometry.{_3D, Point}
import scalismo.io.MeshIO
import scalismo.registration.{ScalingSpace, RotationSpace}

import scala.language.implicitConversions
import scalismo.geometry.Point.implicits._
import java.io.File
import org.scalatest.FunSpec
import org.scalatest.matchers.ShouldMatchers
import breeze.linalg.DenseVector


class MeshTests extends FunSpec with ShouldMatchers {

  implicit def doubleToFloat(d: Double) = d.toFloat

  scalismo.initialize()

  describe("a mesh") {
    val path = getClass.getResource("/facemesh.h5").getPath
    val facemesh = MeshIO.readHDF5(new File(path)).get

    it("finds the right closest points for all the points that define the mesh") {

      for ((pt, id) <- facemesh.points.zipWithIndex) {
        val (closestPt, closestId) = facemesh.findClosestPoint(pt)
        assert(closestPt === pt)
        assert(closestId === id)
      }
    }
    it("finds the right closest point for a point that is not defined on the mesh") {
      val pts = IndexedSeq(Point(0.0, 0.0, 0.0), Point(1.0, 1.0, 1.0), Point(1.0, 1.0, 5.0))
      val cells = IndexedSeq(TriangleCell(0, 1, 2))
      val mesh = TriangleMesh(pts, cells)

      val newPt = Point(1.1, 1.1, 4)
      val (closestPt, closestPtId) = mesh.findClosestPoint(newPt)
      assert(closestPtId === 2)
      assert(closestPt === pts(2))
    }
    it("computes its area correctly for a triangle") {
      val pts: IndexedSeq[Point[_3D]] = IndexedSeq((0.0f, 0.0f, 0.0f), (0.0f, 1.0f, 0.0f), (1.0f, 0.0f, 0.0f))
      val cells = IndexedSeq(TriangleCell(0, 1, 2))
      val mesh = TriangleMesh(pts, cells)

      val R = RotationSpace[_3D]((0.0f, 0.0f, 0.0f)).transformForParameters(DenseVector(0.3, 0.4, 0.1))
      val s = ScalingSpace[_3D].transformForParameters(DenseVector(2.0))
      val transformedMesh = mesh.transform(R).transform(s)
      mesh.area should be(0.5 plusOrMinus 1e-8)
      transformedMesh.area should be(4.0f * mesh.area plusOrMinus 1e-5) // scaling by two gives 4 times the area 
    }

    // ignored until more meaningful test (It's normal that more points are deleted)
    ignore("can be clipped") {
      def ptIdSmallerThan100(pt: Point[_3D]) = facemesh.findClosestPoint(pt)._2 < 100
      val clippedMesh = Mesh.clipMesh(facemesh, ptIdSmallerThan100)

      clippedMesh.numberOfPoints should be(facemesh.numberOfPoints - 100)
    }

    it("computes the right binary image for the unit sphere") {
      val path = getClass.getResource("/unit-sphere.vtk").getPath
      val spheremesh = MeshIO.readMesh(new File(path)).get
      val binaryImg = Mesh.meshToBinaryImage(spheremesh)
      binaryImg(Point(0, 0, 0)) should be(1)
      binaryImg(Point(2, 0, 0)) should be(0)
    }
  }
}