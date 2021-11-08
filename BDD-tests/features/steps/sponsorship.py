from behave import *
import tools
import pytest


@given('{user1} is not sponsoring {user2}')
def step_impl(context, user1, user2):
    try:
        assert tools.isSponsoring(user1, user2) == False
    except:
        tools.cancelSponsorship(user2, user1)

@given('{user1} is sponsoring {user2}')
def step_impl(context, user1, user2):
    try:
        assert tools.isSponsoring(user1, user2) == True
    except:
        tools.sponsor(user2, user1)


@when('{user1} tries to sponsor {user2}')
def step_impl(context, user1, user2):
    try:
        tools.sponsor(user2, user1)
    except:
        pass

@when('{user1} tries to cancel the sponsorship for {user2}')
def step_impl(context, user1, user2):
    try:
        tools.cancelSponsorship(user2, user1)
    except:
        pass

@when('{user1} sponsors {user2}')
def step_impl(context, user1, user2):
    tools.sponsor(user2, user1)


@when('{user1} cancel the sponsorship for {user2}')
def step_impl(context, user1, user2):
    tools.cancelSponsorship(user2, user1)


@then('{user1} is sponsoring {user2}')
def step_impl(context, user1, user2):
    assert tools.isSponsoring(user1, user2) is True


@then('{user1} is not sponsoring {user2}')
def step_impl(context, user1, user2):
    assert tools.isSponsoring(user1, user2) is False
