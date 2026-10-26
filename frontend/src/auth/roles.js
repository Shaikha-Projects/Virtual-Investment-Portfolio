import { ApiError } from '../api/client.js'

const profile = ['profile', 'Profile', 'profile']
const workspaces = {
  ADMIN: {
    home: 'admin',
    navigation: [
      ['admin', 'Admin Dashboard', 'shield'],
      ['admin-assets', 'Asset Management', 'assets'],
      ['admin-users', 'User Management', 'users'],
      ['admin-audit-logs', 'Audit Logs', 'clock'],
      profile,
    ],
    investmentNotifications: false,
  },
  USER: {
    home: 'dashboard',
    navigation: [
      ['dashboard', 'Dashboard', 'dashboard'],
      ['assets', 'Assets', 'assets'],
      ['holdings', 'My Holdings', 'holdings'],
      ['transactions', 'Transactions', 'transactions'],
      ['orders', 'My Orders', 'clock'],
      ['watchlist', 'Watchlist', 'watchlist'],
      profile,
    ],
    investmentNotifications: true,
  },
}

export function workspaceFor(role) {
  return Object.hasOwn(workspaces, role) ? workspaces[role] : null
}

export function sectionFor(role, requested) {
  const workspace = workspaceFor(role)
  if (!workspace) return null
  return workspace.navigation.some(([section]) => section === requested) ? requested : workspace.home
}

export function validateIdentity(identity) {
  if (!workspaceFor(identity?.role) || typeof identity?.emailAddress !== 'string' || !identity.emailAddress.trim()) {
    throw new ApiError('We could not confirm your account role. Please retry the session check or sign in again.')
  }
  return identity
}
