/*
 * Copyright 2016 The BigDL Authors.
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

package com.intel.analytics.bigdl.optim

import java.nio.file.{Files, Paths}

import com.intel.analytics.bigdl._
import com.intel.analytics.bigdl.dataset.{DataSet, SampleToMiniBatch, _}
import com.intel.analytics.bigdl.tensor.Tensor
import com.intel.analytics.bigdl.tensor.TensorNumericMath.TensorNumeric
import com.intel.analytics.bigdl.utils._
import com.intel.analytics.bigdl.visualization.{TrainSummary, ValidationSummary}
import org.apache.spark.rdd.RDD

import scala.reflect.ClassTag

/**
 * [[Optimizer]] is an abstract class which is used to train a model automatically
 * with some certain optimization algorithms.
 *
 * @param model the model to be trained
 * @param dataset the data set used to train a model
 * @param criterion the criterion used to evaluate the loss of the model given an input
 * @tparam T numeric type, which can be [[Float]] or [[Double]]
 * @tparam D the type of elements in DataSet, such as [[MiniBatch]]
 */
// TODO: remove D to be MiniBatch[T]
abstract class Optimizer[T: ClassTag, D](
  protected var model: Module[T],
  protected var dataset: DataSet[D],
  protected var criterion: Criterion[T])(implicit ev : TensorNumeric[T])
{
  protected var state: Table = T()
  protected var optimMethod: OptimMethod[T] = new SGD[T]()
  protected var endWhen: Trigger = Trigger.maxIteration(100)

  protected var checkpointTrigger: Option[Trigger] = None
  protected var checkpointPath: Option[String] = None
  protected var isOverWrite: Boolean = false

  protected var validationTrigger: Option[Trigger] = None
  protected var validationMethods: Option[Array[ValidationMethod[T]]] = None
  protected var validationDataSet: Option[DataSet[MiniBatch[T]]] = None

  // To save the summaries.
  protected var trainSummary: Option[TrainSummary] = None
  protected var validationSummary: Option[ValidationSummary] = None

  // To achieve better performance, please set dropPercentage as 0.04
  protected var dropPercentage: Double = 0.0
  protected var maxDropPercentage: Double = 0.0
  protected var computeThresholdbatchSize: Int = 100
  protected var warmupIterationNum: Int = 200

  protected val gradientClippingParams = GradientClippingParams(false, 0.0f, 0.0f, false, 0.0f)

  /**
   * Trigger the optimization process
   * @return the model to be trained
   */
  def optimize(): Module[T]

  /**
   * make optimizer not check the singleton model on a node
   * @return
   */
  @deprecated("Use bigdl.check.singleton instead", "0.1.0")
  def disableCheckSingleton(): this.type = {
    this.checkSingleton = false
    println("disableCheckSingleton is deprecated. Please use bigdl.check.singleton instead")
    this
  }

  // TODO: Remove below code to DistriOptimizer after disableCheckSingleton is not supported
  protected var checkSingleton = System.getProperty("bigdl.check.singleton",
    false.toString).toBoolean

  /**
   * Set a validate evaluation
   *
   * @param trigger how often to evaluation validation set
   * @param dataset validate data set in type of [[DataSet]] of [[MiniBatch]]
   * @param vMethods a set of validation method [[ValidationMethod]]
   * @return this optimizer
   */
  def setValidation(trigger: Trigger, dataset: DataSet[MiniBatch[T]],
    vMethods : Array[ValidationMethod[T]])
  : this.type = {
    this.validationTrigger = Some(trigger)
    this.validationDataSet = Some(dataset)
    this.validationMethods = Some(vMethods)
    this
  }

  /**
   * Set a validate evaluation
   *
   * @param trigger how often to evaluation validation set
   * @param sampleRDD validate data set in type of [[RDD]] of [[Sample]]
   * @param vMethods a set of validation method [[ValidationMethod]]
   * @param batchSize batch size
   * @param featurePaddingParam feature padding strategy, see
   *                            [[com.intel.analytics.bigdl.dataset.PaddingParam]] for details.
   * @param labelPaddingParam   label padding strategy, see
   *                            [[com.intel.analytics.bigdl.dataset.PaddingParam]] for details.
   *
   * @return this optimizer
   */
  def setValidation(trigger: Trigger, sampleRDD: RDD[Sample[T]],
    vMethods : Array[ValidationMethod[T]], batchSize: Int,
    featurePaddingParam: PaddingParam[T],
    labelPaddingParam: PaddingParam[T]
  ): this.type = {
    this.validationTrigger = Some(trigger)
    val dataSet =
      (DataSet.rdd(sampleRDD) ->
        SampleToMiniBatch(batchSize, Some(featurePaddingParam), Some(labelPaddingParam)))
        .asInstanceOf[DistributedDataSet[MiniBatch[T]]]
    this.validationDataSet = Some(dataSet)
    this.validationMethods = Some(vMethods)
    this
  }

  /**
   * Set a validate evaluation
   *
   * @param trigger how often to evaluation validation set
   * @param sampleRDD validate data set in type of [[RDD]] of [[Sample]]
   * @param vMethods a set of validation method [[ValidationMethod]]
   * @param batchSize batch size
   * @return this optimizer
   */
  def setValidation(trigger: Trigger, sampleRDD: RDD[Sample[T]],
      vMethods : Array[ValidationMethod[T]], batchSize: Int)
  : this.type = {
    this.validationTrigger = Some(trigger)
    val dataSet =
      (DataSet.rdd(sampleRDD) -> SampleToMiniBatch(batchSize))
        .asInstanceOf[DistributedDataSet[MiniBatch[T]]]
    this.validationDataSet = Some(dataSet)
    this.validationMethods = Some(vMethods)
    this
  }

  /**
   * Set validate evaluation
   * @param trigger how often to evaluation validation set
   * @param sampleRDD validate data set in type of [[RDD]] of [[Sample]]
   * @param vMethods a set of validation method [[ValidationMethod]]
   * @param batchSize batch size
   * @param miniBatch construct MiniBatch with a specified miniBatch type
   * @return
   */
  def setValidation(trigger: Trigger, sampleRDD: RDD[Sample[T]],
                    vMethods : Array[ValidationMethod[T]], batchSize: Int, miniBatch: MiniBatch[T])
  : this.type = {
    this.validationTrigger = Some(trigger)
    val dataSet =
      (DataSet.rdd(sampleRDD) -> SampleToMiniBatch(miniBatch, batchSize, None))
        .asInstanceOf[DistributedDataSet[MiniBatch[T]]]
    this.validationDataSet = Some(dataSet)
    this.validationMethods = Some(vMethods)
    this
  }

  /**
   * Set a check point saved at `path` triggered by `trigger`
   *
   * @param path the directory to save
   * @param trigger how often to save the check point
   * @return the optimizer
   */
  def setCheckpoint(path: String, trigger: Trigger): this.type = {
    if (!path.startsWith(File.hdfsPrefix)) {
      require(Files.isDirectory(Paths.get(path)), s"Optimizer.setCheckpoint: $path is not a folder")
    }
    this.checkpointPath = Some(path)
    this.checkpointTrigger = Some(trigger)
    this
  }

  /**
   * Get the directory of saving checkpoint
   */
  def getCheckpointPath(): Option[String] = {
    this.checkpointPath
  }

  /**
   * Enable train summary.
   */
  def setTrainSummary(trainSummary: TrainSummary): this.type = {
    this.trainSummary = Some(trainSummary)
    this
  }

  /**
   * Enable validation summary.
   */
  def setValidationSummary(validationSummary: ValidationSummary): this.type = {
    this.validationSummary = Some(validationSummary)
    this
  }

  /**
   * Enable overwrite saving checkpoint
   */
  def overWriteCheckpoint() : this.type = {
    isOverWrite = true
    this
  }

  private def resetEpoch(): Unit = {
    optimMethod.state.update("epoch", 1)
    optimMethod.state.update("neval", 1)
    optimMethod.state.update("Loss", Float.PositiveInfinity)
    optimMethod.state.update("score", 0f)
    optimMethod.state.update("recordsProcessedThisEpoch", 0)
  }


  /**
   * Set a model to the optimizer
   *
   * @param newModel new model
   */
  def setModel(newModel: Module[T]): this.type = {
    model = newModel
    // if a new Model is set, then reset "epoch", "neval" .etc.
    resetEpoch()
    this
  }


  /**
   * Set new train dataset.
   * User can supply a customized implementation of trait MiniBatch to define
   * how data is organized and retrieved in a mini batch.
   *
   * @param sampleRDD training Samples
   * @param batchSize mini batch size
   * @param miniBatchImpl An User-Defined MiniBatch implementation.
   * @return the Optimizer
   */
  def setTrainData(sampleRDD: RDD[Sample[T]],
                 batchSize: Int,
                 miniBatchImpl: MiniBatch[T]): this.type = {
    throw new UnsupportedOperationException(
      s"setTrainData(sampleRDD, batchSize,miniBatch) " +
        s"is only supported in distributed optimizer")
    this
  }

  /**
   * Set new train dataset.
   *
   * @param sampleRDD           training Samples
   * @param batchSize           mini batch size
   * @param featurePaddingParam feature padding strategy, see
   *                            [[com.intel.analytics.bigdl.dataset.PaddingParam]] for details.
   * @param labelPaddingParam   label padding strategy, see
   *                            [[com.intel.analytics.bigdl.dataset.PaddingParam]] for details.
   * @return the optimizer
   */
  def setTrainData(sampleRDD: RDD[Sample[T]],
                 batchSize: Int,
                 featurePaddingParam: PaddingParam[T] = null,
                 labelPaddingParam: PaddingParam[T] = null): this.type = {
    throw new UnsupportedOperationException(
      s"setTrainData(sampleRDD,batchSize,featurePaddingParam=null,labelPaddingParam=null) " +
        s"is only supported in distributed optimizer")
    this
  }


  /**
   * Set a new criterion to the optimizer
   *
   * @param newCriterion new criterion
   */
  def setCriterion(newCriterion: Criterion[T]): this.type = {
    this.criterion = newCriterion
    this
  }


  /**
   * Set a state(learning rate, epochs...) to the optimizer
   *
   * @param state the state to be saved
   */
  def setState(state: Table): this.type = {
    this.state = state
    this
  }

  /**
   * Set an optimization method
   *
   * @param method optimization method
   */
  def setOptimMethod(method : OptimMethod[T]): this.type = {
    this.optimMethod = method
    this
  }

  /**
   * When to stop, passed in a [[Trigger]]
   *
   * @param endWhen when to end
   * @return the optimizer
   */
  def setEndWhen(endWhen: Trigger): this.type = {
    this.endWhen = endWhen
    this
  }

  /**
   * Set dropping a certain percentage (`dropPercentage`) of models during distributed
   * training to accelerate, because some cached model may take too long.
   *
   * @param dropPercentage drop percentage
   * @param maxDropPercentage max drop percentage
   * @param batchsize batch size
   * @param warmupIteration how may iteration to warm up
   * @return this optimizer
   */
  def setDropModuleProperty(dropPercentage: Double, maxDropPercentage: Double,
    batchsize: Int = 100, warmupIteration: Int = 200): this.type = {
    this.dropPercentage = dropPercentage
    this.maxDropPercentage = maxDropPercentage
    require(dropPercentage >= 0 && dropPercentage <= maxDropPercentage)
    this.computeThresholdbatchSize = batchsize
    this.warmupIterationNum = warmupIteration
    this
  }

  def prepareInput(): Unit = {}

  /**
   * Disable gradient clipping
   * @return
   */
  def disableGradientClipping()
  : this.type = {
    gradientClippingParams.enableConstantClipping = false
    gradientClippingParams.enableL2NormClipping = false
    this
  }

  /**
   * Set constant gradient clipping
   * @param min the minimum value to clip by
   * @param max the maximum value to clip by
   * @return
   */
  def setConstantGradientClipping(min: Float, max: Float)
  : this.type = {
    require(min < max, "min value must be smaller than max")
    gradientClippingParams.enableConstantClipping = true
    gradientClippingParams.minValueClip = min
    gradientClippingParams.maxValueClip = max
    this
  }

  /**
   * Clip gradient to a maximum L2-norm
   * @param clipNorm gradient L2-Norm threshold
   * @return
   */
  def setGradientClippingByl2Norm(clipNorm: Float)
  : this.type = {
    gradientClippingParams.enableL2NormClipping = true
    gradientClippingParams.normValueClip = clipNorm
    this
  }
}

object Optimizer {
  private[bigdl] def header(epoch: Int, count: Int, total: Long, iter: Int, wallClockTime: Long)
  : String = {
    s"[Epoch $epoch $count/$total][Iteration $iter][Wall Clock ${wallClockTime / 1e9}s]"
  }

  /**
   * Save a model to a directory as a checkpoint
   *
   * @param model the model to be saved
   * @param checkpointPath the directory to save at
   * @param overWrite if save name model exists in the directory,
   *                  is overwrite or not.
   * @param postfix the postfix of a model name
   * @tparam T model data type [[Double]] or [[Float]]
   */
  private[bigdl] def saveModel[T](model: Module[T], checkpointPath : Option[String],
    overWrite : Boolean, postfix: String = ""): Unit = {
    if (checkpointPath.isDefined) {
      model.save(s"${checkpointPath.get}/model$postfix", overWrite)
    }
  }

  /**
   * Save a state to a directory as a checkpoint
   *
   * @param state the state (learning rate, epochs...) to be saved
   * @param checkpointPath the directory to save at
   * @param overWrite if save name model exists in the directory,
   *                  is overwrite or not.
   * @param postfix the postfix of a state name
   */
  private[bigdl] def saveState(state: Table, checkpointPath : Option[String], overWrite : Boolean,
    postfix: String = ""): Unit = {
    if (checkpointPath.isDefined) {
      state.save(s"${checkpointPath.get}/state$postfix", overWrite)
    }
  }

  /**
   * Save OptimMethod to a directory as a checkpoint
   * @param optimMethod the method to be saved
   * @param checkpointPath the directory to save at
   * @param overWrite if save name method exists in the directory,
   *                  is overwrite or not.
   * @param postfix the postfix of a method name
   * @tparam T
   */
  private[bigdl] def saveOptimMethod[T: ClassTag]
  (optimMethod: OptimMethod[T], checkpointPath : Option[String], overWrite : Boolean,
   postfix: String = ""): Unit = {
    if (checkpointPath.isDefined) {
      optimMethod.save(s"${checkpointPath.get}/optimMethod$postfix", overWrite)
    }
  }


  /**
   * Apply an Optimizer.
   *
   * @param model               model will be optimized
   * @param sampleRDD           training Samples
   * @param criterion           loss function
   * @param batchSize           mini batch size
   * @param featurePaddingParam feature padding strategy, see
   *                            [[com.intel.analytics.bigdl.dataset.PaddingParam]] for details.
   * @param labelPaddingParam   label padding strategy, see
   *                            [[com.intel.analytics.bigdl.dataset.PaddingParam]] for details.
   * @return An optimizer
   */
  def apply[T: ClassTag](
      model: Module[T],
      sampleRDD: RDD[Sample[T]],
      criterion: Criterion[T],
      batchSize: Int,
      featurePaddingParam: PaddingParam[T] = null,
      labelPaddingParam: PaddingParam[T] = null
         )(implicit ev: TensorNumeric[T]): Optimizer[T, MiniBatch[T]] = {

    val _featurePaddingParam = if (featurePaddingParam != null) Some(featurePaddingParam) else None
    val _labelPaddingParam = if (labelPaddingParam != null) Some(labelPaddingParam) else None

    new DistriOptimizer[T](
       _model = model,
       _dataset = (DataSet.rdd(sampleRDD) ->
         SampleToMiniBatch(batchSize, _featurePaddingParam, _labelPaddingParam))
         .asInstanceOf[DistributedDataSet[MiniBatch[T]]],
       _criterion = criterion
     ).asInstanceOf[Optimizer[T, MiniBatch[T]]]
  }


  /**
   * Apply an optimizer.
   * User can supply a customized implementation of trait MiniBatch to define
   * how data is organize and retrieved in a mini batch.
   *
   * @param model model will be optimized
   * @param sampleRDD training Samples
   * @param criterion loss function
   * @param batchSize mini batch size
   * @param miniBatchImpl An User-Defined MiniBatch implementation
   * @return an new Optimizer
   */
  def apply[T: ClassTag](
          model: Module[T],
          sampleRDD: RDD[Sample[T]],
          criterion: Criterion[T],
          batchSize: Int,
          miniBatchImpl: MiniBatch[T]
        )(implicit ev: TensorNumeric[T]): Optimizer[T, MiniBatch[T]] = {
    new DistriOptimizer[T](
      _model = model,
      _dataset = (DataSet.rdd(sampleRDD) ->
        SampleToMiniBatch(miniBatchImpl, batchSize, None))
        .asInstanceOf[DistributedDataSet[MiniBatch[T]]],
      _criterion = criterion
    ).asInstanceOf[Optimizer[T, MiniBatch[T]]]
  }

  /**
   * Apply an optimizer.
   *
   * @param model model will be optimizied
   * @param dataset the input dataset - determines the type of optimizer
   * @param criterion loss function
   * @return an new Optimizer
   */
  def apply[T: ClassTag, D](
    model: Module[T],
    dataset: DataSet[D],
    criterion: Criterion[T]
  )(implicit ev: TensorNumeric[T]): Optimizer[T, D] = {
    dataset match {
      case d: DistributedDataSet[_] =>
        new DistriOptimizer[T](
          _model = model,
          _dataset = d.asInstanceOf[DistributedDataSet[MiniBatch[T]]],
          _criterion = criterion
        ).asInstanceOf[Optimizer[T, D]]
      case d: LocalDataSet[_] =>
        new LocalOptimizer[T](
          model = model,
          dataset = d.asInstanceOf[LocalDataSet[MiniBatch[T]]],
          criterion = criterion
        ).asInstanceOf[Optimizer[T, D]]
      case _ =>
        throw new UnsupportedOperationException
    }
  }
}

case class GradientClippingParams(
   var enableConstantClipping: Boolean,
   var minValueClip: Float,
   var maxValueClip: Float,
   var enableL2NormClipping: Boolean,
   var normValueClip: Float)
