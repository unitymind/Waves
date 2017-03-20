package com.wavesplatform.state2.diffs

import com.wavesplatform.settings.FunctionalitySettings
import com.wavesplatform.state2.Diff
import com.wavesplatform.state2.reader.StateReader
import scorex.transaction._
import scorex.transaction.assets.{BurnTransaction, IssueTransaction, ReissueTransaction, TransferTransaction}

object TransactionDiffer {
  def apply(settings: FunctionalitySettings, time: Long, height: Int)(s: StateReader, tx: Transaction): Either[ValidationError, Diff] = {
    for {
      transaction <- CommonValidation(s, settings, time, tx)
      diff <- transaction match {
        case gtx: GenesisTransaction => GenesisTransactionDiff(height)(gtx)
        case ptx: PaymentTransaction => PaymentTransactionDiff(s, settings, height)(ptx)
        case itx: IssueTransaction => AssetTransactionsDiff.issue(s, height)(itx)
        case rtx: ReissueTransaction => AssetTransactionsDiff.reissue(s, height)(rtx)
        case btx: BurnTransaction => AssetTransactionsDiff.burn(s, height)(btx)
        case ttx: TransferTransaction => TransferTransactionDiff(s, height)(ttx)
        case _ => ???
      }
      positiveDiff <- BalanceDiffValidation(s, settings, time)(tx, diff)
    } yield positiveDiff
  }
}

