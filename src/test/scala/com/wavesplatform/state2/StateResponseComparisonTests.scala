package com.wavesplatform.state2

import com.wavesplatform.settings.{BlockchainSettings, FunctionalitySettings, GenesisSettings}
import org.h2.mvstore.MVStore
import org.scalatest.{FreeSpec, Matchers}
import scorex.account.{Account, AddressScheme}
import scorex.transaction._
import scorex.transaction.state.database.BlockStorageImpl
import scorex.transaction.state.database.blockchain.{StoredBlockchain, StoredState, ValidatorImpl}
import scorex.transaction.state.database.state.storage._

class StateResponseComparisonTests extends FreeSpec with Matchers {

  import StateResponseComparisonTests._

  AddressScheme.current = new AddressScheme {
    override val chainId: Byte = 'W'
  }

  "provide the same answers to questions after each block from mainnet applied" ignore {
    val oldStore = BlockStorageImpl.createMVStore("")
    val old = storedBC(oldState(oldStore), new StoredBlockchain(oldStore))

    val newStore = BlockStorageImpl.createMVStore("")
    val nev = storedBC(newState(newStore), new StoredBlockchain(newStore))

    val currentMainnetStore = BlockStorageImpl.createMVStore(BlocksOnDisk)
    val currentMainnet = storedBC(oldState(currentMainnetStore), new StoredBlockchain(currentMainnetStore))

    val CHECK_BLOCKS = Seq(100)
    val APPLY_TO = 28001

    // 0 doesn't exist, 1 is genesis
    val end = currentMainnet.history.height() + 1
    Range(1, APPLY_TO).foreach { blockNumber =>
      s"[$blockNumber]" - {
        def block = currentMainnet.history.blockAt(blockNumber).get

        "[OLD] Block appended successfully" in {
          val oldTime = withTime(old.appendBlock(block).get)._1
        }
        "[NEW] New appended successfully" in {
          val newTime = withTime(nev.appendBlock(block).get)._1
        }
        if (CHECK_BLOCKS contains blockNumber) {
          validateState(old, nev)
        }
      }
    }
  }

  "block application time measure" ignore {
    val currentMainnetStore = BlockStorageImpl.createMVStore(BlocksOnDisk)
    val currentMainnet = storedBC(oldState(currentMainnetStore), new StoredBlockchain(currentMainnetStore))
    val end = currentMainnet.history.height() + 1
    applyFirstBlocks(end)
  }

  "assert state" in {
    val (old, nev) = {
      val currentMainnetStore = BlockStorageImpl.createMVStore(BlocksOnDisk)
      val currentMainnet = storedBC(oldState(currentMainnetStore), new StoredBlockchain(currentMainnetStore))
      val end = currentMainnet.history.height() + 1
      applyFirstBlocks(end)
    }
    validateState(old, nev)

  }
}

object StateResponseComparisonTests extends FreeSpec {
  val BlocksOnDisk = "C:\\Users\\ilyas\\waves\\data\\blockchain.dat"

  def oldState(mvStore: MVStore): State = {

    val storage = new MVStoreStateStorage
      with MVStoreOrderMatchStorage
      with MVStoreAssetsExtendedStateStorage
      with MVStoreLeaseExtendedStateStorage
      with MVStoreAliasExtendedStorage {
      override val db: MVStore = mvStore
      if (db.getStoreVersion > 0) {
        db.rollback()
      }
    }
    new StoredState(storage, FunctionalitySettings.MAINNET)
  }

  def newState(mVStore: MVStore): State = new StateWriterAdapter(
    new StateWriterImpl(new MVStorePrimitiveImpl(mVStore)), FunctionalitySettings.MAINNET)

  val settings = BlockchainSettings("", 'W', FunctionalitySettings.MAINNET, GenesisSettings.MAINNET)

  def storedBC(theState: State, theHistory: History): BlockStorage = {
    val blockStorageImpl = new BlockStorageImpl(settings) {
      override val history: History = theHistory
      override val state: State = theState
    }
    blockStorageImpl
  }

  def withTime[R](r: => R): (Long, R) = {
    val t0 = System.currentTimeMillis()
    val rr = r
    val t1 = System.currentTimeMillis()
    (t1 - t0, rr)
  }

  def logStep(step: Int, total: Int): Unit = {
    if (step % (total / 10) == 0) {
      println(s"$step of $total..")
    }
  }

  def validateState(old: BlockStorage, nev: BlockStorage): Unit = {
    val block = old.history.lastBlock

    val aliveAccounts = old.state.wavesDistributionAtHeight(old.state.stateHeight)
      .map(_._1)
      .map(Account.fromString(_).right.get)
      .toIndexedSeq

    s"findTransaction and included" in {
      Range(1, old.state.stateHeight).foreach(idx => {
        logStep(idx, old.state.stateHeight)
        val oldBlock = old.history.blockAt(idx).get
        oldBlock.transactionData.foreach { tx =>
          assert(nev.state.included(tx.id).contains(nev.state.stateHeight))
          assert(nev.state.findTransaction[Transaction](tx.id).contains(tx))

        }
      })


      s"accountTransactions" in {
        for (accIdx <- aliveAccounts.indices) {
          logStep(accIdx, aliveAccounts.size)
          val acc = aliveAccounts(accIdx)
          val oldtxs = old.state.accountTransactions(acc, Int.MaxValue).toList
          val newtxs = nev.state.accountTransactions(acc, Int.MaxValue).toList
          assert(oldtxs.size == newtxs.size, s"acc: ${acc.stringRepr}")
          oldtxs.indices.foreach { i =>
            // we do not assert the actual order here, is it wrong?
            // assert(oldtxs(i).id sameElements newtxs(i).id, s"i = $i")
            assert(newtxs.exists(tx => tx.id sameElements oldtxs(i).id))
          }
        }
      }
      s"lastAccountPaymentTransaction" in {
        for (accIdx <- aliveAccounts.indices) {
          logStep(accIdx, aliveAccounts.size)
          val acc = aliveAccounts(accIdx)
          val oldPtx = old.state.lastAccountPaymentTransaction(acc)
          val nevPtx = nev.state.lastAccountPaymentTransaction(acc)
          val areSame = oldPtx == nevPtx
          assert(areSame, acc.stringRepr + "\n" + "OLD: " + oldPtx + "\n" + "NEW: " + nevPtx)
        }
      }
      s"balance, effectiveBalance, leasedSum" in {
        for (accIdx <- aliveAccounts.indices) {
          logStep(accIdx, aliveAccounts.size)
          val acc = aliveAccounts(accIdx)
          val oldBalance = old.state.balance(acc)
          val newBalance = nev.state.balance(acc)
          assert(oldBalance == newBalance, s"old=$oldBalance new=$newBalance acc: $acc")
          assert(old.state.effectiveBalance(acc) == nev.state.effectiveBalance(acc))
          assert(old.state.getLeasedSum(acc.stringRepr) == nev.state.getLeasedSum(acc.stringRepr))
        }
      }

      s"getAccountBalance, assetBalance" in {
        for (accIdx <- aliveAccounts.indices) {
          logStep(accIdx, aliveAccounts.size)
          val acc = aliveAccounts(accIdx)
          val oldAccBalance = old.state.getAccountBalance(acc).map { case (k, v) => EqByteArray(k) -> v }
          val newAccBalance = nev.state.getAccountBalance(acc).map { case (k, v) => EqByteArray(k) -> v }
          assert(oldAccBalance == newAccBalance)

          val oldAssetAccs = oldAccBalance.map(_._1.arr).map(aid => AssetAcc(acc, Some(aid)))

          for (assetAcc <- oldAssetAccs) {
            assert(old.state.assetBalance(assetAcc) == nev.state.assetBalance(assetAcc))
          }
        }
      }

      s"isReissuable, totalAssetQuantity" in {
        val eqAssetIds = aliveAccounts.flatMap(acc => old.state.getAccountBalance(acc).keySet.map(EqByteArray)).toIndexedSeq
        for (eqAssetIdIdx <- eqAssetIds.indices) {
          val eqAssetId = eqAssetIds(eqAssetIdIdx)
          val assetId = eqAssetId.arr
          assert(old.state.isReissuable(assetId) == nev.state.isReissuable(assetId))
          assert(old.state.totalAssetQuantity(assetId) == nev.state.totalAssetQuantity(assetId))
        }
      }

      "height" in {
        assert(old.state.stateHeight == nev.state.stateHeight)
      }

      s"effectiveBalanceWithConfirmations" in {
        for {
          accIdx <- aliveAccounts.indices
          _ = logStep(accIdx, aliveAccounts.size)
          acc = aliveAccounts(accIdx)
          confs <- Seq(50, 1000)
          oldEBWC = old.state.effectiveBalanceWithConfirmations(acc, confs, old.state.stateHeight)
          newEBWC = nev.state.effectiveBalanceWithConfirmations(acc, confs, nev.state.stateHeight)

        } yield {
          assert(oldEBWC == newEBWC, s"acc=$acc old=$oldEBWC new=$newEBWC")
        }
      }
    }
  }

  def applyFirstBlocks(amount: Int): (BlockStorage, BlockStorage) = {
    val oldStore = BlockStorageImpl.createMVStore("")
    val old = storedBC(oldState(oldStore), new StoredBlockchain(oldStore))

    val newStore = BlockStorageImpl.createMVStore("")
    val nev = storedBC(newState(newStore), new StoredBlockchain(newStore))

    val currentMainnetStore = BlockStorageImpl.createMVStore(BlocksOnDisk)
    val currentMainnet = storedBC(oldState(currentMainnetStore), new StoredBlockchain(currentMainnetStore))

    val end = currentMainnet.history.height() + 1

    val (t0, _) = withTime(Range(1, end).foreach { blockNumber =>
      def block = currentMainnet.history.blockAt(blockNumber).get

      val impl = new ValidatorImpl(old.state, FunctionalitySettings.MAINNET)
      impl.validate(block.transactionData, None, block.timestamp)

      old.appendBlock(block).get
      if (blockNumber % 10000 == 0) {
        println(blockNumber)
      }
    })
    println("old time " + t0)
    val (t1, _) = withTime(Range(1, end).foreach { blockNumber =>
      def block = currentMainnet.history.blockAt(blockNumber).get

      nev.appendBlock(block).get
      if (blockNumber % 10000 == 0) {
        println(blockNumber)
      }
    })
    println("--------------")
    println("old time " + t0)
    println("new time " + t1)
    (old, nev)
  }
}