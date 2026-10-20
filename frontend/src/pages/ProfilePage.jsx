import Icon from '../components/Icon'
import { useAction } from '../hooks/useAction'
import { useState } from 'react'
import { pictureUrl } from '../api/client'
import { useResource } from '../hooks/useResource'
import { Field, Notice, Resource } from '../components/UI'

export default function ProfilePage({ api, revision, refresh }) {
  const resource = useResource(api, '/profile', revision)
  const [editing, setEditing] = useState(false)
  const [passwordForm, setPasswordForm] = useState(false)
  const [fileKey, setFileKey] = useState(0)
  const action = useAction()
  const profile = resource.data
  const update = async event => {
    event.preventDefault()
    const body = Object.fromEntries(new FormData(event.currentTarget))
    if (!body.firstName.trim() || !body.lastName.trim()) { action.warn('First and last names are required.'); return }
    body.phoneNumber ||= null
    if (await action.run(() => api('/profile', { method: 'PATCH', body }), 'Profile updated successfully.')) { setEditing(false); refresh() }
  }
  const password = async event => {
    event.preventDefault()
    const body = Object.fromEntries(new FormData(event.currentTarget))
    if (await action.run(() => api('/auth/change-password', { method: 'POST', body }), 'Password changed successfully.')) setPasswordForm(false)
  }
  const upload = async event => {
    event.preventDefault()
    const body = new FormData(event.currentTarget)
    const file = body.get('picture')
    if (!file?.size || !file.type.startsWith('image/')) { action.warn('Please select an image file.'); return }
    if (file.size > 5 * 1024 * 1024) { action.warn('Choose an image under 5 MB.'); return }
    if (await action.run(() => api('/profile/picture', { method: 'PUT', body }), 'Profile picture updated.')) { setFileKey(value => value + 1); refresh() }
  }
  return <section className="card profile-page"><div className="page-heading"><div><span className="eyebrow">Account settings</span><h2>My Profile</h2><p className="muted">A space for your details, preferences, and account security.</p></div><span className="icon-tile"><Icon name="profile" /></span></div><Notice action={action} /><Resource resource={resource}>
    {profile && <><div className="profile-card"><div className="profile-avatar">{profile.profilePicture ? <img src={pictureUrl(profile.profilePicture)} alt="Your profile" /> : `${profile.firstName?.[0] || ''}${profile.lastName?.[0] || ''}`}</div><dl className="profile-details">{[['First name', profile.firstName], ['Last name', profile.lastName], ['Email address', profile.emailAddress], ['Phone number', profile.phoneNumber || 'Not provided']].map(([label, value]) => <div key={label}><dt>{label}</dt><dd>{value}</dd></div>)}</dl></div>
    <div className="actions"><button disabled={action.pending} onClick={() => { action.clear(); setEditing(value => !value); setPasswordForm(false) }}>Edit Profile</button><button className="secondary" disabled={action.pending} onClick={() => { action.clear(); setPasswordForm(value => !value); setEditing(false) }}>Change Password</button></div>
    {editing && <form className="form-stack sub-form" onSubmit={update}><h3>Edit Profile</h3><Field label="First name" name="firstName" defaultValue={profile.firstName} required /><Field label="Last name" name="lastName" defaultValue={profile.lastName} required /><Field label="Phone number" name="phoneNumber" type="tel" pattern="[0-9]{8}" title="Exactly 8 digits" defaultValue={profile.phoneNumber} /><div className="actions"><button disabled={action.pending}>Save changes</button><button type="button" className="secondary" disabled={action.pending} onClick={() => { setEditing(false); action.clear() }}>Cancel</button></div></form>}
    {passwordForm && <form className="form-stack sub-form" onSubmit={password}><h3><Icon name="lock" size={19} />Change Password</h3><Field label="Current password" name="currentPassword" type="password" autoComplete="current-password" required /><Field label="New password" name="newPassword" type="password" autoComplete="new-password" minLength={8} required /><div className="actions"><button disabled={action.pending}>Change password</button><button type="button" className="secondary" disabled={action.pending} onClick={() => { setPasswordForm(false); action.clear() }}>Cancel</button></div></form>}
    <form key={fileKey} className="form-stack sub-form" onSubmit={upload}><h3><Icon name="profile" size={19} />Profile Picture</h3><Field label="Choose image (maximum 5 MB)" name="picture" type="file" accept="image/*" required /><button disabled={action.pending}>Upload picture</button></form></>}
  </Resource></section>
}
