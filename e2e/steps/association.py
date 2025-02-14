from behave import *
from e2e.common.tools import broadcast, NODE, funds_for_transaction
from lto.transactions import Association, RevokeAssociation


def association(context, sender, recipient, type, hash="", version=None):
    sender = context.users[sender]
    recipient = context.users[recipient]
    
    transaction = Association(recipient.address, association_type=type, anchor=hash)
    transaction.version = version or Association.DEFAULT_VERSION
    transaction.sign_with(sender)

    broadcast(context, transaction)


def is_associated(context, sender, recipient):
    sender = context.users[sender]
    recipient = context.users[recipient]

    list_outgoing = NODE.wrapper(api='/associations/status/{}'.format(sender.address))['outgoing']
    ass_list = []
    for association in list_outgoing:
        if 'revokeTransactionId' not in association and association['party'] == recipient.address:
            association['sender'] = sender.address
            ass_list.append(association)
    return ass_list


def revoke_association(context, user1, user2, type, hash="", version=None):
    user1 = context.users[user1]
    user2 = context.users[user2]

    transaction = RevokeAssociation(recipient=user2.address, association_type=type, anchor=hash)
    transaction.version = version or RevokeAssociation.DEFAULT_VERSION
    transaction.sign_with(user1)

    broadcast(context, transaction)


@given('{sender} has an association with {recipient} of type {type:d}')
@given('{sender} has an association with {recipient} of type {type:d} and anchor {hash}')
def step_impl(context, sender, recipient, type, hash=""):
    if not is_associated(context, sender, recipient):
        funds_for_transaction(context, sender, Association.DEFAULT_FEE)
        association(context, sender, recipient, type, hash)
        assert is_associated(context, sender, recipient), 'Failed to issue association'


@given('{sender} does not have an association with {recipient} of type {type:d}')
def step_impl(context, sender, recipient, type):
    if is_associated(context, sender, recipient):
        funds_for_transaction(context, sender, RevokeAssociation.DEFAULT_FEE)
        revoke_association(sender, recipient, type, hash)
        assert not is_associated(context, sender, recipient, type), 'Failed to revoke association'


@when('{sender} issues an association with {recipient} of type {type:d}')
@when('{sender} issues an association (v{version:d}) with {recipient} of type {type:d}')
def step_impl(context, sender, recipient, type, version=None):
    association(context, sender, recipient, type, version=version)


@when('{sender} revokes the association with {recipient} of type {type:d}')
@when('{sender} revokes the association (v{version:d}) with {recipient} of type {type:d}')
def step_impl(context, sender, recipient, type, version=None):
    revoke_association(context, sender, recipient, type, version=version)


@when('{sender} revokes the association with {recipient} of type {type:d} and anchor {hash}')
def step_impl(context, sender, recipient, type, hash):
    revoke_association(context, sender, recipient, type, hash)


@when(u'{sender} tries to issue an association with {recipient} of type {type:d}')
def step_impl(context, sender, recipient, type):
    try:
        association(context, sender, recipient, type)
    except:
        pass


@when(u'{sender} tries to revoke an association with {recipient} of type {type:d}')
def step_impl(context, sender, recipient, type):
    try:
        revoke_association(context, sender, recipient, type)
    except:
        pass


@then('{sender} is associated with {recipient}')
def step_impl(context, sender, recipient):
    value = is_associated(context, sender, recipient)
    assert value, '{} is not associated with {}'.format(context.users[sender].address, context.users[recipient].address)


@then('{sender} is not associated with {recipient}')
def step_impl(context, sender, recipient):
    value = is_associated(context, sender, recipient)
    assert not value, f'{value}'
