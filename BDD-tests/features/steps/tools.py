from LTO.Accounts.AccountFactoryED25519 import AccountED25519 as AccountFactory
from LTO.PublicNode import PublicNode
from LTO.Transactions.Transfer import Transfer
from LTO.Transactions.Anchor import Anchor
from LTO.Transactions.Sponsorship import Sponsorship
from LTO.Transactions.CancelSponsorship import CancelSponsorship
from LTO.Transactions.Lease import Lease
from LTO.Transactions.CancelLease import CancelLease
import random
import polling
import requests
import hashlib

CHAIN_ID = 'T'
URL = 'https://testnet.lto.network'
NODE = PublicNode(URL)
ROOT_SEED = 'fragile because fox snap picnic mean art observe vicious program chicken purse text hidden chest'
TRANSFER_FEE = 100000000

lastTransactionSuccess = None


def getBalance(seed):
    account = AccountFactory(CHAIN_ID).createFromSeed(getSeed(seed))
    return NODE.balance(account.address)


def pollTx(id):
    return polling.poll(
        lambda: requests.get('%s%s' % (URL, ('/transactions/info/%s' % id)), headers='').json(),
        check_success=lambda response: 'id' in response,
        step=1,
        timeout=180
    )


def transferTo(recipient=ROOT_SEED, amount=0, sender=ROOT_SEED):
    global lastTransactionSuccess

    recipientAccount = AccountFactory(CHAIN_ID).createFromSeed(getSeed(recipient))
    senderAccount = AccountFactory(CHAIN_ID).createFromSeed(getSeed(sender))
    transaction = Transfer(recipientAccount.address, amount)
    transaction.signWith(senderAccount)
    try:
        tx = transaction.broadcastTo(NODE)
        pollTx(tx.id)
        lastTransactionSuccess = True
        return tx
    except:
        lastTransactionSuccess = False
        raise


def anchor(seed=ROOT_SEED, hash=""):
    global lastTransactionSuccess

    account = AccountFactory(CHAIN_ID).createFromSeed(getSeed(seed))
    if not hash:
        hash = ''.join(random.choice('qwertyuioplkjhgfds') for _ in range(6))
    transaction = Anchor(encodeHash(hash))
    transaction.signWith(account)

    try:
        tx = transaction.broadcastTo(NODE)
        pollTx(tx.id)
        lastTransactionSuccess = True
        return tx
    except:
        lastTransactionSuccess = False
        raise


def isLastTransactionSuccessful():
    return lastTransactionSuccess


def getSeed(name):
    if len(name) >= 15:
        return name
    else:
        while len(name) < 15:
            name = name + "a"
        return name


def convertBalance(balance):
    return int(float(balance) * 100000000)


def encodeHash(hash):
    return hashlib.sha256(hash.encode('utf-8')).hexdigest()


def isSponsoring(account1, account2):
    account1 = AccountFactory(CHAIN_ID).createFromSeed(getSeed(account1))
    account2 = AccountFactory(CHAIN_ID).createFromSeed(getSeed(account2))
    return account1.address in NODE.sponsorshipList(account2.address)['sponsor']


def isLeasing(account1, account2):
    account1 = AccountFactory(CHAIN_ID).createFromSeed(getSeed(account1))
    account2 = AccountFactory(CHAIN_ID).createFromSeed(getSeed(account2))
    leasList = NODE.leaseList(account1.address)
    for lease in leasList:
        if lease['recipient'] == account2.address:
            return True
    return False


def getLeaseId(account1, account2):
    leasList = NODE.leaseList(account1.address)
    for lease in leasList:
        if lease['recipient'] == account2.address:
            return lease['id']
    raise Exception("No Lease Id Found")


def sponsor(sponsored, sponsoring):
    global lastTransactionSuccess

    sponsored = AccountFactory(CHAIN_ID).createFromSeed(getSeed(sponsored))
    sponsoring = AccountFactory(CHAIN_ID).createFromSeed(getSeed(sponsoring))
    transaction = Sponsorship(sponsored.address)
    transaction.signWith(sponsoring)

    try:
        tx = transaction.broadcastTo(NODE)
        pollTx(tx.id)
        lastTransactionSuccess = True
        return tx
    except:
        lastTransactionSuccess = False
        raise


def cancelSponsorship(sponsored, sponsoring):
    global lastTransactionSuccess
    sponsored = AccountFactory(CHAIN_ID).createFromSeed(getSeed(sponsored))
    sponsoring = AccountFactory(CHAIN_ID).createFromSeed(getSeed(sponsoring))
    transaction = CancelSponsorship(sponsored.address)
    transaction.signWith(sponsoring)
    try:
        tx = transaction.broadcastTo(NODE)
        pollTx(tx.id)
        lastTransactionSuccess = True
        return tx
    except:
        lastTransactionSuccess = False
        raise

def cancelLease(account1, account2):
    global lastTransactionSuccess

    account1 = AccountFactory(CHAIN_ID).createFromSeed(getSeed(account1))
    account2 = AccountFactory(CHAIN_ID).createFromSeed(getSeed(account2))

    leaseId = getLeaseId(account1, account2)
    transaction = CancelLease(leaseId)
    transaction.signWith(account1)
    try:
        tx = transaction.broadcastTo(NODE)
        pollTx(tx.id)
        lastTransactionSuccess = True
        return tx
    except:
        lastTransactionSuccess = False
        raise


def lease(account1, account2, amount=100000000):
    global lastTransactionSuccess

    amount = int(amount)
    account1 = AccountFactory(CHAIN_ID).createFromSeed(getSeed(account1))
    account2 = AccountFactory(CHAIN_ID).createFromSeed(getSeed(account2))

    transaction = Lease(recipient=account2.address, amount=amount)
    transaction.signWith(account1)
    try:
        tx = transaction.broadcastTo(NODE)
        pollTx(tx.id)
        lastTransactionSuccess = True
        return tx
    except:
        lastTransactionSuccess = False
        raise


