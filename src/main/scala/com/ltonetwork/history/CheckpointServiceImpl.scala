package com.ltonetwork.history

import com.ltonetwork.crypto
import com.ltonetwork.db.{CheckpointCodec, PropertiesStorage, SubStorage}
import com.ltonetwork.network.Checkpoint
import com.ltonetwork.settings.CheckpointsSettings
import com.ltonetwork.transaction.ValidationError.GenericError
import com.ltonetwork.transaction.{CheckpointService, ValidationError}
import org.iq80.leveldb.DB

class CheckpointServiceImpl(db: DB, settings: CheckpointsSettings)
    extends SubStorage(db, "checkpoints")
    with PropertiesStorage
    with CheckpointService {

  private val CheckpointProperty = "checkpoint"

  override def get: Option[Checkpoint] = getProperty(CheckpointProperty).flatMap(b => CheckpointCodec.decode(b).toOption.map(r => r.value))

  override def set(cp: Checkpoint): Either[ValidationError, Unit] =
    for {
      _ <- Either.cond(!get.forall(_.signature sameElements cp.signature), (), GenericError("Checkpoint already applied"))
      _ <- Either.cond(
        crypto.verify(cp.signature, cp.toSign, settings.publicKey.arr),
        putProperty(CheckpointProperty, CheckpointCodec.encode(cp), None),
        GenericError("Invalid checkpoint signature")
      )
    } yield ()

}
