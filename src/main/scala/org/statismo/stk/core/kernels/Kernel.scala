package org.statismo.stk.core
package kernels

import breeze.linalg.{ pinv, diag, DenseMatrix }
import org.statismo.stk.core.image.DiscreteImageDomain1D
import breeze.linalg.DenseVector
import org.statismo.stk.core.numerics.RandomSVD
import org.statismo.stk.core.image.DiscreteImageDomain
import breeze.plot.Figure
import org.statismo.stk.core.numerics.Sampler
import org.statismo.stk.core.common.DiscreteDomain
import org.statismo.stk.core.common.BoxedDomain
import org.statismo.stk.core.common.ImmutableLRU
import org.statismo.stk.core.geometry._

abstract class PDKernel[D <: Dim] { self =>
  def apply(x: Point[D], y: Point[D]): Double
  def outputDim: Int = 1
  
    def +(that: PDKernel[D]): PDKernel[D] = new PDKernel[D] {
    override def apply(x: Point[D], y: Point[D]) = self.apply(x, y) + that.apply(x, y)
  }

  def *(that: PDKernel[D]): PDKernel[D] = new PDKernel[D] {
    override def apply(x: Point[D], y: Point[D]) = self.apply(x, y) * that.apply(x, y)
  }

  def *(s: Double): PDKernel[D] = new PDKernel[D] {
    override def apply(x: Point[D], y: Point[D]) = self.apply(x, y) * s
  }

  // TODO this could be made more generic by allowing the input of phi to be any type A
  def compose(phi: Point[D] => Point[D]) = new PDKernel[D] {
    override def apply(x: Point[D], y: Point[D]) = self.apply(phi(x), phi(y))
  }

}

abstract class MatrixValuedPDKernel[D <: Dim, DO <: Dim : DimTraits] { self =>
  val oDimTraits = implicitly[DimTraits[DO]]
  
  def apply(x: Point[D], y: Point[D]): MatrixNxN[DO]
  def outputDim = oDimTraits.dimensionality

  def +(that: MatrixValuedPDKernel[D, DO]): MatrixValuedPDKernel[D, DO] = new MatrixValuedPDKernel[D, DO] {
    override def apply(x: Point[D], y: Point[D]) = self.apply(x, y) + that.apply(x, y)
  }

  def *(that: MatrixValuedPDKernel[D, DO]): MatrixValuedPDKernel[D, DO] = new MatrixValuedPDKernel[D, DO] {
    override def apply(x: Point[D], y: Point[D]) = self.apply(x, y) :* that.apply(x, y)
  }

  def *(s: Double): MatrixValuedPDKernel[D, DO] = new MatrixValuedPDKernel[D, DO] {
    override def apply(x: Point[D], y: Point[D]) = self.apply(x, y) * s
  }

  // TODO this could be made more generic by allowing the input of phi to be any type A
  def compose(phi: Point[D] => Point[D]) = new MatrixValuedPDKernel[D, DO] {
    override def apply(x: Point[D], y: Point[D]) = self.apply(phi(x), phi(y))
  }

}

case class UncorrelatedKernel1x1(k: PDKernel[OneD]) extends MatrixValuedPDKernel[OneD, OneD]{
  val I = Matrix1x1.eye
  def apply(x: Point[OneD], y: Point[OneD]) = I * (k(x, y)) // k is scalar valued
}

case class UncorrelatedKernel2x2(k: PDKernel[TwoD]) extends MatrixValuedPDKernel[TwoD, TwoD]{
  val I = Matrix2x2.eye
  def apply(x: Point[TwoD], y: Point[TwoD]) = I * (k(x, y)) // k is scalar valued
}

case class UncorrelatedKernel3x3(k: PDKernel[ThreeD]) extends MatrixValuedPDKernel[ThreeD, ThreeD] {
  val I = Matrix3x3.eye
  def apply(x: Point[ThreeD], y: Point[ThreeD]) = I * (k(x, y)) // k is scalar valued
}

case class GaussianKernel3D(val sigma: Double) extends PDKernel[ThreeD] {
  val sigma2 = sigma * sigma
  def apply(x: Point[ThreeD], y: Point[ThreeD]) = {

    val r0 = (x(0) - y(0))
    val r1 = (x(1) - y(1))
    val r2 = (x(2) - y(2))
    val normr2 = r0 * r0 + r1 * r1 + r2 * r2 // ||x -y ||^2
    scala.math.exp(-normr2 / sigma2)
  }
}

case class GaussianKernel2D(val sigma: Double) extends PDKernel[TwoD] {
  val sigma2 = sigma * sigma
  def apply(x: Point[TwoD], y: Point[TwoD]) = {

    val r0 = (x(0) - y(0))
    val r1 = (x(1) - y(1))
    val normr2 = r0 * r0 + r1 * r1 // ||x -y ||^2
    scala.math.exp(-normr2 / sigma2)
  }
}

case class GaussianKernel1D(val sigma: Double) extends PDKernel[OneD] {

  val sigma2 = sigma * sigma

  def apply(x: Point[OneD], y: Point[OneD]) = {

    val r = (x(0) - y(0))
    scala.math.exp(-(r * r) / sigma2)
  }
}

object Kernel {

  def computeKernelMatrix[D <: Dim](xs: IndexedSeq[Point[D]], k: MatrixValuedPDKernel[D, D]): DenseMatrix[Float] = {
    val d = k.outputDim

    val K = DenseMatrix.zeros[Float](xs.size * d, xs.size * d)
    for { (xi, i) <- xs.zipWithIndex; (xj, j) <- xs.zipWithIndex; di <- 0 until d; dj <- 0 until d } {
      K(i * d + di, j * d + dj) = k(xi, xj)(di, dj)
      K(j * d + dj, i * d + di) = K(i * d + di, j * d + dj)
    }
    K
  }

  /**
   * for every domain point x in the list, we compute the kernel vector
   * kx = (k(x, x1), ... k(x, xm))
   * since the kernel is matrix valued, kx is actually a matrix
   */
  def computeKernelVectorFor[D <: Dim](x: Point[D], xs: IndexedSeq[Point[D]], k: MatrixValuedPDKernel[D, D]): DenseMatrix[Float] = {
    val d = k.outputDim

    val kxs = DenseMatrix.zeros[Float](d, xs.size * d)

    var j = 0
    while (j < xs.size) {
      var di = 0
      while (di < d) {
        var dj = 0
        while (dj < d) {
          kxs(di, j * d + dj) = k(xs(j), x)(di, dj)
          dj += 1
        }
        di += 1
      }
      j += 1
    }

    kxs
  }

  def computeNystromApproximation[D <: Dim : DimTraits](k: MatrixValuedPDKernel[D, D], numBasisFunctions: Int, numPointsForNystrom: Int, sampler: Sampler[D, Point[D]]): IndexedSeq[(Float, Point[D] => Vector[D])] = {

    // procedure for the nystrom approximation as described in 
    // Gaussian Processes for machine Learning (Rasmussen and Williamson), Chapter 4, Page 99

    val volumeOfSampleRegion = sampler.volumeOfSampleRegion

    val (ptsForNystrom, _) = sampler.sample(numPointsForNystrom).unzip

    // deppending on the sampler, it may happen that we did not sample all the points we wanted 
    val effectiveNumberOfPointsSampled = ptsForNystrom.size

    val kernelMatrix = computeKernelMatrix(ptsForNystrom, k)
    val (uMat, lambdaMat, _) = RandomSVD.computeSVD(kernelMatrix.map(_.toDouble), numBasisFunctions)

    // TODO check that this is also correct for non-rectangular domains
    val lambda = lambdaMat.map(lmbda => (volumeOfSampleRegion / effectiveNumberOfPointsSampled.toDouble) * lmbda)
    val numParams = (for (i <- (0 until lambda.size) if lambda(i) >= 1e-8) yield 1).size

    val W = (uMat(::, 0 until numParams) * math.sqrt(effectiveNumberOfPointsSampled / volumeOfSampleRegion) * pinv(diag(lambdaMat(0 until numParams)))).map(_.toFloat)

    @volatile
    var cache = ImmutableLRU[Point[D], DenseMatrix[Float]](1000)
    def phi(i: Int)(x: Point[D]) = {
      // check the cache. if value is not there exit
      // TODO make it nicer using scalaz Memo class
      // TODO make cache size configurable
      val (maybeKx, _) = cache.get(x)
      val value = maybeKx.getOrElse {
        val newValue = computeKernelVectorFor(x, ptsForNystrom, k) * W
        cache = (cache + (x, newValue))._2 // ignore evicted key
        newValue
      }
      // return an indexed seq containing with elements corresponding to the i deformations
      val dfAsBreezeVector = value(::, i).map(_.toFloat)
      
      // convert it to a vector of the right size
      implicitly[DimTraits[D]].createVector(dfAsBreezeVector.data)
      
    }

    val lambdaISeq = lambda(0 until numParams).map(_.toFloat).toArray.toIndexedSeq
    val phis = (0 until numParams).map(i => phi(i)_)
    lambdaISeq.zip(phis)
  }

}