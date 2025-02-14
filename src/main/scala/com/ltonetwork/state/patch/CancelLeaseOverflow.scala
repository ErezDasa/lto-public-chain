package com.ltonetwork.state.patch

import com.ltonetwork.state.{Blockchain, Diff, LeaseBalance, Portfolio}
import com.ltonetwork.transaction.lease.LeaseTransaction
import com.ltonetwork.utils.ScorexLogging

object CancelLeaseOverflow extends ScorexLogging {
  def apply(blockchain: Blockchain): Diff = {
    log.info("Cancelling all lease overflows for sender")

    val addressesWithLeaseOverflow = blockchain.collectLposPortfolios {
      case (_, p) if p.balance < p.lease.out => Portfolio(0, LeaseBalance(0, -p.lease.out))
    }
    addressesWithLeaseOverflow.keys.foreach(addr => log.info(s"Resetting lease overflow for $addr"))

    val leasesToCancel = addressesWithLeaseOverflow.keys.flatMap { a =>
      blockchain.addressTransactions(a, Set(LeaseTransaction.typeId), Int.MaxValue, 0).collect {
        case (_, lt: LeaseTransaction) if lt.sender.toAddress == a => lt.id()
      }
    }
    leasesToCancel.foreach(id => log.info(s"Cancelling lease $id"))

    log.info("Finished cancelling all lease overflows for sender")

    Diff.empty.copy(portfolios = addressesWithLeaseOverflow, leaseState = leasesToCancel.map(_ -> false).toMap)
  }
}
