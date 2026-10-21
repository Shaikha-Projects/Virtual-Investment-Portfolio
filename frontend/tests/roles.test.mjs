import test from 'node:test'
import assert from 'node:assert/strict'
import { sectionFor, validateIdentity, workspaceFor } from '../src/auth/roles.js'

test('admin workspace contains only administration pages and shared profile', () => {
  const workspace = workspaceFor('ADMIN')
  assert.equal(workspace.home, 'admin')
  assert.deepEqual(workspace.navigation.map(([section]) => section), ['admin', 'admin-assets', 'admin-users', 'profile'])
  assert.equal(workspace.investmentNotifications, false)
  for (const section of ['dashboard', 'assets', 'holdings', 'transactions', 'orders', 'watchlist']) {
    assert.equal(sectionFor('ADMIN', section), 'admin')
  }
  assert.equal(sectionFor('ADMIN', 'profile'), 'profile')
})

test('investor workspace contains no administration pages', () => {
  const workspace = workspaceFor('USER')
  assert.equal(workspace.home, 'dashboard')
  assert.deepEqual(workspace.navigation.map(([section]) => section), ['dashboard', 'assets', 'holdings', 'transactions', 'orders', 'watchlist', 'profile'])
  assert.equal(workspace.investmentNotifications, true)
  for (const section of ['admin', 'admin-assets', 'admin-users']) assert.equal(sectionFor('USER', section), 'dashboard')
  assert.equal(sectionFor('USER', 'profile'), 'profile')
})

test('missing, unsupported and prototype property roles never default to an investor workspace', () => {
  for (const role of [null, undefined, '', 'MANAGER', 'admin', '__proto__', 'constructor', 'toString']) {
    assert.equal(workspaceFor(role), null)
    assert.equal(sectionFor(role, 'profile'), null)
    assert.throws(() => validateIdentity({ role, emailAddress: 'account@example.test' }), /could not confirm your account role/)
  }
  assert.throws(() => validateIdentity(null), /could not confirm/)
  assert.throws(() => validateIdentity({ role: 'ADMIN' }), /could not confirm/)
})

test('identity role comes from the server, independent of email address', () => {
  for (const role of ['ADMIN', 'USER']) {
    const identity = { emailAddress: 'same-account@example.test', role }
    assert.equal(validateIdentity(identity).role, role)
  }
})

test('unknown section state resolves to each role own dashboard', () => {
  for (const section of ['invalid', '__proto__', 'constructor', undefined]) {
    assert.equal(sectionFor('ADMIN', section), 'admin')
    assert.equal(sectionFor('USER', section), 'dashboard')
  }
})
