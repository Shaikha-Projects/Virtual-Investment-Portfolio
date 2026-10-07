import { useState } from 'react'
import './App.css'

function App() {
  const [emailAddress, setEmailAddress] = useState('')
  const [password, setPassword] = useState('')
  const [message, setMessage] = useState('')
  const [isLoggedIn, setIsLoggedIn] = useState(false)
  const [assets, setAssets] = useState([])
  const [holdings, setHoldings] = useState([])
  const [activeSection, setActiveSection] = useState('')
  const [performance, setPerformance] = useState(null)
  const [selectedAsset, setSelectedAsset] = useState(null)
  const [buyQuantity, setBuyQuantity] = useState('')
  const [buyMessage, setBuyMessage] = useState('')
  const [selectedHolding, setSelectedHolding] = useState(null)
  const [sellQuantity, setSellQuantity] = useState('')
  const [sellMessage, setSellMessage] = useState('')
  const [transactions, setTransactions] = useState([])
  const [watchlist, setWatchlist] = useState([])
  const [watchlistMessage, setWatchlistMessage] = useState('')
  const [transactionSymbol, setTransactionSymbol] = useState('')
  const [transactionType, setTransactionType] = useState('')
  const [sortColumn, setSortColumn] = useState('')
  const [sortDirection, setSortDirection] = useState('asc')
  const [profile, setProfile] = useState(null)
  const [isEditingProfile, setIsEditingProfile] = useState(false)
  const [editFirstName, setEditFirstName] = useState('')
  const [editLastName, setEditLastName] = useState('')
  const [editPhoneNumber, setEditPhoneNumber] = useState('')
  const [profileMessage, setProfileMessage] = useState('')
  const [profilePictureFile, setProfilePictureFile] = useState(null)
  const [pictureMessage, setPictureMessage] = useState('')
  const initialResetToken =
      new URLSearchParams(window.location.search).get('token') || ''

  const [authMode, setAuthMode] = useState(
      initialResetToken ? 'reset' : 'login'
  )

  const [registerFirstName, setRegisterFirstName] = useState('')
  const [registerLastName, setRegisterLastName] = useState('')
  const [registerEmail, setRegisterEmail] = useState('')
  const [registerPhone, setRegisterPhone] = useState('')
  const [registerPassword, setRegisterPassword] = useState('')
  const [registerMessage, setRegisterMessage] = useState('')

  const [forgotEmail, setForgotEmail] = useState('')
  const [forgotMessage, setForgotMessage] = useState('')

  const [resetToken, setResetToken] = useState(initialResetToken)
  const [newPassword, setNewPassword] = useState('')
  const [resetMessage, setResetMessage] = useState('')

  const [showChangePassword, setShowChangePassword] = useState(false)
  const [currentPassword, setCurrentPassword] = useState('')
  const [changeNewPassword, setChangeNewPassword] = useState('')
  const [changePasswordMessage, setChangePasswordMessage] = useState('')
  const [changePasswordSuccess, setChangePasswordSuccess] = useState(false)


  //login function
  const handleLogin = async () => {

    const response = await fetch('http://localhost:8081/auth/login', {
      method: 'POST',

      headers: {
        'Content-Type': 'application/json'
      },

      body: JSON.stringify({
        emailAddress: emailAddress,
        password: password
      })
    })

    const data = await response.json()

    if (response.ok) {
      localStorage.setItem('token', data.token)
      setMessage(data.message)
      setIsLoggedIn(true)
      await loadDashboard(data.token)
    } else {
      setMessage(data.message)
    }

  }

  //view assets
  const handleViewAssets = async () => {

    setBuyMessage('')
    setWatchlistMessage('')

    const token = localStorage.getItem('token')

    const response = await fetch('http://localhost:8081/assets', {
      method: 'GET',
      headers: {
        'Authorization': `Bearer ${token}`
      }
    })

    const data = await response.json()

    if (response.ok) {
      setAssets(data)
      setActiveSection('assets')
    }
  }

  //view holdings
  const handleViewHoldings = async () => {

    const token = localStorage.getItem('token')

    const response = await fetch('http://localhost:8081/portfolio/holdings', {
      method: 'GET',
      headers: {
        'Authorization': `Bearer ${token}`
      }
    })

    const data = await response.json()

    if (response.ok) {
      setHoldings(data)
      setActiveSection('holdings')
    }
  }

  //load dashboard after login
  const loadDashboard = async (token) => {

    const response = await fetch('http://localhost:8081/portfolio/performance', {
      method: 'GET',
      headers: {
        'Authorization': `Bearer ${token}`
      }
    })

    const data = await response.json()

    if (response.ok) {
      setPerformance(data)
      setActiveSection('dashboard')
    }
  }

  //view portfolio performance
  const handleDashboard = async () => {

    const token = localStorage.getItem('token')

    const response = await fetch('http://localhost:8081/portfolio/performance', {
      method: 'GET',
      headers: {
        'Authorization': `Bearer ${token}`
      }
    })

    const data = await response.json()

    if (response.ok) {
      setPerformance(data)
      setActiveSection('dashboard')
    }
  }

  //buy asset
  const handleBuyAsset = async () => {

    const token = localStorage.getItem('token')

    const response = await fetch('http://localhost:8081/portfolio/buy', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Authorization': `Bearer ${token}`
      },
      body: JSON.stringify({
        assetId: selectedAsset.assetId,
        quantity: Number(buyQuantity)
      })
    })

    const data = await response.json()

    if (response.ok) {
      setBuyMessage(`Successfully purchased ${buyQuantity} share(s) of ${selectedAsset.symbol}.`)
      setTimeout(() => {
        setBuyMessage('')
      }, 3000)
      setSelectedAsset(null)
      setBuyQuantity('')

    } else {
      setBuyMessage(data.message || 'Purchase failed.')
    }
  }

  const handleLogout = () => {
    localStorage.removeItem('token')
    setIsLoggedIn(false)
    setActiveSection('')
    setPerformance(null)
    setMessage('')
  }

  //sell asset
  const handleSellAsset = async () => {

    const token = localStorage.getItem('token')

    const response = await fetch('http://localhost:8081/portfolio/sell', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Authorization': `Bearer ${token}`
      },
      body: JSON.stringify({
        assetId: selectedHolding.assetId,
        quantity: Number(sellQuantity)
      })
    })
    const data = await response.json()

    if (response.ok) {
      setSellMessage(
          `Successfully sold ${sellQuantity} share(s) of ${selectedHolding.assetSymbol}.`
      )
      setTimeout(() => {
        setSellMessage('')
      }, 3000)
      setSelectedHolding(null)
      setSellQuantity('')

      //refresh holdings after selling
      await handleViewHoldings()
    }
    else {
      setSellMessage(data.message || 'Sell failed.')
    }
  }

  //view transaction history
  const handleViewTransactions = async () => {

    const token = localStorage.getItem('token')

    let url = 'http://localhost:8081/portfolio/transactions?'

    if (transactionType) {
      url += `type=${transactionType}&`
    }

    if (transactionSymbol) {
      url += `symbol=${transactionSymbol}&`
    }

    if (sortColumn) {
      url += `sort=${sortColumn},${sortDirection}&`
    }

    const response = await fetch(url, {
      method: 'GET',
      headers: {
        'Authorization': `Bearer ${token}`
      }
    })

    const data = await response.json()

    if (response.ok) {
      setTransactions(data.content)
      setActiveSection('transactions')
    }
  }

  //transaction sort
  const handleTransactionSort = async (column) => {
    let newColumn = column
    let newDirection = 'asc'

    if (sortColumn === column) {
      if (sortDirection === 'asc') {
        newDirection = 'desc'
      } else {
        // Third click: reset sorting
        newColumn = ''
        newDirection = 'asc'
      }
    }

    setSortColumn(newColumn)
    setSortDirection(newDirection)

    const token = localStorage.getItem('token')

    let url = 'http://localhost:8081/portfolio/transactions?'

    if (transactionType) {
      url += `type=${transactionType}&`
    }

    if (transactionSymbol) {
      url += `symbol=${transactionSymbol}&`
    }

    if (newColumn) {
      url += `sort=${newColumn},${newDirection}&`
    }

    const response = await fetch(url, {
      method: 'GET',
      headers: {
        'Authorization': `Bearer ${token}`
      }
    })

    const data = await response.json()

    if (response.ok) {
      setTransactions(data.content)
    }
  }

  //view watchlist
  const handleViewWatchlist = async () => {

    const token = localStorage.getItem('token')

    const response = await fetch('http://localhost:8081/watchlist', {
      method: 'GET',
      headers: {
        'Authorization': `Bearer ${token}`
      }
    })

    const data = await response.json()

    if (response.ok) {
      setWatchlist(data)
      setActiveSection('watchlist')
    }
  }

  //remove from watchlist
  const handleRemoveWatchlist = async (watchlistId) => {

    const token = localStorage.getItem('token')

    const response = await fetch(
        `http://localhost:8081/watchlist/${watchlistId}`,
        {
          method: 'DELETE',
          headers: {
            'Authorization': `Bearer ${token}`
          }
        }
    )

    if (response.ok) {
      //refresh watchlist
      await handleViewWatchlist()
    }
  }

  //add asset to watchlist
  const handleAddWatchlist = async (asset) => {
    setBuyMessage('')
    setWatchlistMessage('')

    const token = localStorage.getItem('token')

    const response = await fetch(
        `http://localhost:8081/watchlist/${asset.assetId}`,
        {
          method: 'POST',
          headers: {
            'Authorization': `Bearer ${token}`
          }
        }
    )

    const data = await response.json()

    if (response.ok) {
      setWatchlistMessage(`${asset.symbol} added to your watchlist.`)
      setTimeout(() => {
        setWatchlistMessage('')
      }, 3000)
    } else {
      setWatchlistMessage(data.message || 'Could not add asset to watchlist.')
      setTimeout(() => {
        setWatchlistMessage('')
      }, 3000)
    }
  }

  //view profile
  const handleViewProfile = async () => {
    setShowChangePassword(false)
    setCurrentPassword('')
    setChangeNewPassword('')
    setChangePasswordMessage('')
    setChangePasswordSuccess(false)

    const token = localStorage.getItem('token')

    const response = await fetch('http://localhost:8081/profile', {
      method: 'GET',
      headers: {
        'Authorization': `Bearer ${token}`
      }
    })

    const data = await response.json()

    if (response.ok) {
      setProfile(data)
      setActiveSection('profile')
    }
  }

  //edit profile
  const handleUpdateProfile = async () => {
    if (!editFirstName.trim()) {
      setProfileMessage('First name cannot be empty.')
      return
    }

    if (!editLastName.trim()) {
      setProfileMessage('Last name cannot be empty.')
      return
    }
    const token = localStorage.getItem('token')

    const response = await fetch('http://localhost:8081/profile', {
      method: 'PATCH',
      headers: {
        'Content-Type': 'application/json',
        'Authorization': `Bearer ${token}`
      },
      body: JSON.stringify({
        firstName: editFirstName,
        lastName: editLastName,
        phoneNumber: editPhoneNumber || null
      })
    })

    const data = await response.json()

    if (response.ok) {
      setProfile(data)
      setIsEditingProfile(false)
      setProfileMessage('Profile updated successfully.')
      setTimeout(() => {
        setProfileMessage('')
      }, 3000)
    } else {
      const errorMessage = Object.values(data).join(' ')
      setProfileMessage(errorMessage || 'Profile update failed.')

    }
  }

  //upload picture
  const handleUploadProfilePicture = async () => {
    if (!profilePictureFile) {
      setPictureMessage('Please choose an image first.')
      return
    }

    const token = localStorage.getItem('token')

    const formData = new FormData()
    formData.append('picture', profilePictureFile)

    const response = await fetch('http://localhost:8081/profile/picture', {
      method: 'PUT',
      headers: {
        'Authorization': `Bearer ${token}`
      },
      body: formData
    })

    const data = await response.json()

    if (response.ok) {
      setProfile(data)
      setProfilePictureFile(null)
      setPictureMessage('Profile picture uploaded successfully.')
    } else {
      setPictureMessage(data.message || 'Profile picture upload failed.')
    }
  }

  //handle register
  const handleRegister = async () => {
    setRegisterMessage('')

    if (!registerFirstName.trim()) {
      setRegisterMessage('First name is required.')
      return
    }

    if (!registerLastName.trim()) {
      setRegisterMessage('Last name is required.')
      return
    }

    if (!/^\d{8}$/.test(registerPhone)) {
      setRegisterMessage('Phone number must be exactly 8 digits.')
      return
    }

    if (!registerEmail.trim()) {
      setRegisterMessage('Email address is required.')
      return
    }

    if (registerPassword.length < 8) {
      setRegisterMessage('Password must be at least 8 characters.')
      return
    }

    const response = await fetch('http://localhost:8081/auth/register', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json'
      },
      body: JSON.stringify({
        firstName: registerFirstName,
        lastName: registerLastName,
        phoneNumber: registerPhone,
        emailAddress: registerEmail,
        password: registerPassword
      })
    })

    const data = await response.json()

    if (response.ok) {
      setRegisterMessage(data.message || 'Registration successful. Please verify your email.')
      setRegisterFirstName('')
      setRegisterLastName('')
      setRegisterPhone('')
      setRegisterEmail('')
      setRegisterPassword('')
    } else {
      const errorMessage =
          data.message || Object.values(data).join(' ')

      setRegisterMessage(errorMessage || 'Registration failed.')
    }
  }

  //handle forget password
  const handleForgotPassword = async () => {
    setForgotMessage('')

    if (!forgotEmail.trim()) {
      setForgotMessage('Email address is required.')
      return
    }

    const response = await fetch('http://localhost:8081/auth/forgot-password', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json'
      },
      body: JSON.stringify({
        emailAddress: forgotEmail
      })
    })

    const data = await response.json()

    if (response.ok) {
      setForgotMessage(
          data.message || 'Password reset email sent successfully.'
      )
    } else {
      setForgotMessage(
          data.message || 'Unable to send password reset email.'
      )
    }
  }

  //handle reset password
  const handleResetPassword = async () => {
    setResetMessage('')

    if (newPassword.length < 8) {
      setResetMessage('Password must be at least 8 characters.')
      return
    }

    const response = await fetch('http://localhost:8081/auth/reset-password', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json'
      },
      body: JSON.stringify({
        token: resetToken,
        newPassword: newPassword
      })
    })

    const data = await response.json()

    if (response.ok) {
      setResetMessage(data.message || 'Password reset successfully.')
      setNewPassword('')

      setTimeout(() => {
        window.history.replaceState({}, '', '/')
        setResetToken('')
        setResetMessage('')
        setAuthMode('login')
      }, 2000)
    } else {
      setResetMessage(
          data.message || 'Password reset failed.'
      )
    }
  }

  //handle change
  const handleChangePassword = async () => {
    setChangePasswordSuccess(false)
    setChangePasswordMessage('')

    if (!currentPassword.trim()) {
      setChangePasswordMessage('Current password is required.')
      return
    }

    if (changeNewPassword.length < 8) {
      setChangePasswordMessage('New password must be at least 8 characters.')
      return
    }

    const token = localStorage.getItem('token')

    const response = await fetch('http://localhost:8081/auth/change-password', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Authorization': `Bearer ${token}`
      },
      body: JSON.stringify({
        currentPassword: currentPassword,
        newPassword: changeNewPassword
      })
    })

    const data = await response.json()

    if (response.ok) {
      setChangePasswordMessage(
          data.message || 'Password changed successfully.'
      )
      setChangePasswordSuccess(true)

      setCurrentPassword('')
      setChangeNewPassword('')
    } else {
      setChangePasswordMessage(
          data.message || 'Password change failed.'
      )
    }
  }

  //dashbored
  if (isLoggedIn) {
    return (
        <div className="dashboard">

          <aside className="sidebar">
            <div className="brand">
              <div className="brand-icon">$</div>

              <div>
                <h2>Virtual Investment Portfolio</h2>
                <span>Virtual Trading</span>
              </div>
            </div>

            <nav className="sidebar-nav">
              <button
                  className={activeSection === 'dashboard' ? 'active' : ''}
                  onClick={handleDashboard}
              >
                Dashboard
              </button>

              <button
                  className={activeSection === 'assets' ? 'active' : ''}
                  onClick={handleViewAssets}
              >
                Assets
              </button>

              <button
                  className={activeSection === 'holdings' ? 'active' : ''}
                  onClick={handleViewHoldings}
              >
                My Holdings
              </button>

              <button
                  className={activeSection === 'transactions' ? 'active' : ''}
                  onClick={handleViewTransactions}
              >
                Transactions
              </button>
              <button
                  className={activeSection === 'watchlist' ? 'active' : ''}
                  onClick={handleViewWatchlist}
              >
                Watchlist
              </button>
              <button
                  className={activeSection === 'profile' ? 'active' : ''}
                  onClick={handleViewProfile}
              >
                Profile
              </button>
            </nav>
          </aside>

          <div className="dashboard-main">

            <header className="topbar">
              <div>
                <h1>Investment Portfolio</h1>
                <p>Manage and track your virtual investments</p>
              </div>

              <button
                  className="logout-button"
                  onClick={handleLogout}
              >
                Logout
              </button>
            </header>

            <main className="content">

              {/* Dashboard */}
              {activeSection === 'dashboard' && performance && (
                  <div className="dashboard-overview">

                    <div className="dashboard-title">
                      <h2>Portfolio Performance Overview</h2>
                      <p>Track your virtual investment performance.</p>
                    </div>

                    <div className="summary-cards">

                      <div className="summary-card">
                        <span>Total Portfolio Value</span>
                        <h2>
                          ${Number(performance.totalPortfolioValue).toLocaleString()}
                        </h2>
                        <p>Total value of your portfolio</p>
                      </div>

                      <div className="summary-card">
                        <span>Cash Balance</span>
                        <h2>
                          ${Number(performance.cashBalance).toLocaleString()}
                        </h2>
                        <p>Available to invest</p>
                      </div>

                      <div className="summary-card">
                        <span>Holdings Value</span>
                        <h2>
                          ${Number(performance.holdingsValue).toLocaleString()}
                        </h2>
                        <p>Current value of investments</p>
                      </div>

                      <div className="summary-card">
                        <span>Unrealized Gain / Loss</span>

                        <h2
                            className={
                              Number(performance.unrealizedGainLoss) >= 0
                                  ? 'positive'
                                  : 'negative'
                            }
                        >
                          {Number(performance.unrealizedGainLoss) >= 0 ? '+' : '-'}$
                          {Math.abs(
                              Number(performance.unrealizedGainLoss)
                          ).toLocaleString()}
                        </h2>

                        <p>Based on current asset prices</p>
                      </div>

                    </div>

                    <div className="performance-card">
                      <div>
                        <span>Total Cost Basis</span>
                        <h3>
                          ${Number(performance.totalCostBasis).toLocaleString()}
                        </h3>
                      </div>

                      <div>
                        <span>Current Holdings Value</span>
                        <h3>
                          ${Number(performance.holdingsValue).toLocaleString()}
                        </h3>
                      </div>

                      <div>
                        <span>Unrealized Gain / Loss</span>
                        <h3
                            className={
                              Number(performance.unrealizedGainLoss) >= 0
                                  ? 'positive'
                                  : 'negative'
                            }
                        >
                          {Number(performance.unrealizedGainLoss) >= 0 ? '+' : '-'}$
                          {Math.abs(
                              Number(performance.unrealizedGainLoss)
                          ).toLocaleString()}
                        </h3>
                      </div>
                    </div>

                  </div>
              )}

              {/* Assets */}
            {activeSection === 'assets' && assets.length > 0 && (
                <div className="card">
                  <h2>Available Assets</h2>

                  {watchlistMessage && (
                      <p className="watchlist-message">
                        {watchlistMessage}
                      </p>
                  )}

                  {buyMessage && (
                      <p className="buy-message">
                        {buyMessage}
                      </p>
                  )}

                  {selectedAsset && (
                      <div className="buy-panel">

                        <div>
                          <h3>Buy {selectedAsset.symbol}</h3>
                          <p>
                            {selectedAsset.name} — ${selectedAsset.currentPrice} per share
                          </p>
                        </div>

                        <input
                            type="number"
                            min="1"
                            placeholder="Quantity"
                            value={buyQuantity}
                            onChange={(e) => setBuyQuantity(e.target.value)}
                        />

                        <button
                            className="confirm-buy-button"
                            onClick={handleBuyAsset}
                        >
                          Confirm Buy
                        </button>

                        <button
                            className="cancel-button"
                            onClick={() => {
                              setSelectedAsset(null)
                              setBuyQuantity('')
                            }}
                        >
                          Cancel
                        </button>

                      </div>
                  )}

                  <table>
                    <thead>
                    <tr>
                      <th>Symbol</th>
                      <th>Name</th>
                      <th>Type</th>
                      <th>Price</th>
                      <th>Status</th>
                      <th>Action</th>
                    </tr>
                    </thead>

                    <tbody>
                    {assets.map((asset) => (
                        <tr key={asset.symbol}>
                          <td>{asset.symbol}</td>
                          <td>{asset.name}</td>
                          <td>{asset.assetType}</td>
                          <td>${asset.currentPrice}</td>
                          <td>{asset.assetStatus}</td>
                          <td>
                            <button
                                className="buy-button"
                                onClick={() => setSelectedAsset(asset)}
                            >
                              Buy
                            </button>

                            <button
                                className="watchlist-button"
                                onClick={() => handleAddWatchlist(asset)}
                            >
                              + Watchlist
                            </button>
                          </td>
                        </tr>
                    ))}
                    </tbody>
                  </table>
                </div>
            )}

              {/* Holdings */}
            {activeSection === 'holdings' && holdings.length > 0 && (
                <div className="card">
                  <h2>My Holdings</h2>
                  {sellMessage && (
                      <p className="sell-message">{sellMessage}</p>
                  )}
                  {selectedHolding && (
                      <div className="sell-panel">

                        <div>
                          <h3>Sell {selectedHolding.assetSymbol}</h3>
                          <p>
                            {selectedHolding.assetName} — You own {selectedHolding.quantity} share(s)
                          </p>
                        </div>

                        <input
                            type="number"
                            min="0.01"
                            placeholder="Quantity"
                            value={sellQuantity}
                            onChange={(e) => setSellQuantity(e.target.value)}
                        />

                        <button
                            className="confirm-sell-button"
                            onClick={handleSellAsset}
                        >
                          Confirm Sell
                        </button>

                        <button
                            className="cancel-button"
                            onClick={() => {
                              setSelectedHolding(null)
                              setSellQuantity('')
                            }}
                        >
                          Cancel
                        </button>

                      </div>
                  )}

                  {activeSection === 'transactions' && (
                      <div className="transactions-section">

                        <h2>Transaction History</h2>
                        <div className="transaction-filters">

                          <input
                              type="text"
                              placeholder="Search by symbol"
                              value={transactionSymbol}
                              onChange={(e) => setTransactionSymbol(e.target.value)}
                          />

                          <select
                              value={transactionType}
                              onChange={(e) => setTransactionType(e.target.value)}
                          >
                            <option value="">All Types</option>
                            <option value="BUY">BUY</option>
                            <option value="SELL">SELL</option>
                          </select>

                          <button onClick={handleViewTransactions}>
                            Search
                          </button>

                        </div>

                        {transactions.length > 0 ? (
                            <table>
                              <thead>
                              <tr>
                                <th>Type</th>
                                <th>Asset</th>

                                <th
                                    onClick={() => handleTransactionSort('quantity')}
                                    style={{ cursor: 'pointer' }}
                                >
                                  Quantity {sortColumn === 'quantity'
                                    ? (sortDirection === 'asc' ? '↑' : '↓')
                                    : '↕'}
                                </th>

                                <th>Price Per Unit</th>
                                <th>Total Amount</th>
                                <th>Status</th>
                                <th>Date</th>
                              </tr>
                              </thead>

                              <tbody>
                              {transactions.map((transaction) => (
                                  <tr key={transaction.transactionId}>
                                    <td>{transaction.transactionType}</td>
                                    <td>{transaction.assetSymbol}</td>
                                    <td>{transaction.quantity}</td>
                                    <td>${transaction.pricePerUnit}</td>
                                    <td>${transaction.totalAmount}</td>
                                    <td>{transaction.transactionStatus}</td>
                                    <td>
                                      {new Date(transaction.createdAt).toLocaleString()}
                                    </td>
                                  </tr>
                              ))}
                              </tbody>
                            </table>
                        ) : (
                            <p>No transactions found.</p>
                        )}

                      </div>
                  )}

                  <table>
                    <thead>
                    <tr>
                      <th>Symbol</th>
                      <th>Asset</th>
                      <th>Quantity</th>
                      <th>Average Buy Price</th>
                      <th>Current Price</th>
                      <th>Current Value</th>
                      <th>Action</th>
                    </tr>
                    </thead>

                    <tbody>
                    {holdings.map((holding) => (
                        <tr key={holding.holdingId}>
                          <td>{holding.assetSymbol}</td>
                          <td>{holding.assetName}</td>
                          <td>{holding.quantity}</td>
                          <td>${holding.averageBuyPrice}</td>
                          <td>${holding.currentPrice}</td>
                          <td>${holding.currentValue}</td>

                          <td>
                            <button
                                className="sell-button"
                                onClick={() => setSelectedHolding(holding)}
                            >
                              Sell
                            </button>
                          </td>
                        </tr>
                    ))}
                    </tbody>
                  </table>
                </div>
            )}

              {/* Transactions */}
              {activeSection === 'transactions' && (
                  <div className="transactions-section">

                    <h2>Transaction History</h2>

                    <div className="transaction-filters">

                      <input
                          type="text"
                          placeholder="Search by symbol"
                          value={transactionSymbol}
                          onChange={(e) => setTransactionSymbol(e.target.value)}
                      />

                      <select
                          value={transactionType}
                          onChange={(e) => setTransactionType(e.target.value)}
                      >
                        <option value="">All Types</option>
                        <option value="BUY">BUY</option>
                        <option value="SELL">SELL</option>
                      </select>

                      <button onClick={handleViewTransactions}>
                        Search
                      </button>

                    </div>

                    {transactions.length > 0 ? (
                        <table>
                          <thead>
                          <tr>
                            <th
                                onClick={() => handleTransactionSort('transactionType')}
                                style={{ cursor: 'pointer' }}
                            >
                              Type {sortColumn === 'transactionType'
                                ? (sortDirection === 'asc' ? '↑' : '↓')
                                : '↕'}
                            </th>

                            <th
                                onClick={() => handleTransactionSort('asset.symbol')}
                                style={{ cursor: 'pointer' }}
                            >
                              Asset {sortColumn === 'asset.symbol'
                                ? (sortDirection === 'asc' ? '↑' : '↓')
                                : '↕'}
                            </th>

                            <th
                                onClick={() => handleTransactionSort('quantity')}
                                style={{ cursor: 'pointer' }}
                            >
                              Quantity {sortColumn === 'quantity'
                                ? (sortDirection === 'asc' ? '↑' : '↓')
                                : '↕'}
                            </th>

                            <th
                                onClick={() => handleTransactionSort('pricePerUnit')}
                                style={{ cursor: 'pointer' }}
                            >
                              Price Per Unit {sortColumn === 'pricePerUnit'
                                ? (sortDirection === 'asc' ? '↑' : '↓')
                                : '↕'}
                            </th>

                            <th
                                onClick={() => handleTransactionSort('totalAmount')}
                                style={{ cursor: 'pointer' }}
                            >
                              Total Amount {sortColumn === 'totalAmount'
                                ? (sortDirection === 'asc' ? '↑' : '↓')
                                : '↕'}
                            </th>

                            <th
                                onClick={() => handleTransactionSort('transactionStatus')}
                                style={{ cursor: 'pointer' }}
                            >
                              Status {sortColumn === 'transactionStatus'
                                ? (sortDirection === 'asc' ? '↑' : '↓')
                                : '↕'}
                            </th>

                            <th
                                onClick={() => handleTransactionSort('createdAt')}
                                style={{ cursor: 'pointer' }}
                            >
                              Date {sortColumn === 'createdAt'
                                ? (sortDirection === 'asc' ? '↑' : '↓')
                                : '↕'}
                            </th>
                          </tr>
                          </thead>

                          <tbody>
                          {transactions.map((transaction) => (
                              <tr key={transaction.transactionId}>
                                <td>{transaction.transactionType}</td>
                                <td>{transaction.assetSymbol}</td>
                                <td>{transaction.quantity}</td>
                                <td>${transaction.pricePerUnit}</td>
                                <td>${transaction.totalAmount}</td>
                                <td>{transaction.transactionStatus}</td>
                                <td>
                                  {new Date(transaction.createdAt).toLocaleString()}
                                </td>
                              </tr>
                          ))}
                          </tbody>
                        </table>
                    ) : (
                        <p>No transactions found.</p>
                    )}

                  </div>
              )}

              {/* Watchlist */}
              {activeSection === 'watchlist' && (
                  <div className="watchlist-section">

                    <h2>My Watchlist</h2>

                    {watchlist.length > 0 ? (
                        <table>
                          <thead>
                          <tr>
                            <th>Symbol</th>
                            <th>Asset</th>
                            <th>Current Price</th>
                            <th>Added Date</th>
                            <th>Action</th>
                          </tr>
                          </thead>

                          <tbody>
                          {watchlist.map((item) => (
                              <tr key={item.watchlistId}>
                                <td>{item.assetSymbol}</td>
                                <td>{item.assetName}</td>
                                <td>${item.currentPrice}</td>
                                <td>
                                  {new Date(item.createdAt).toLocaleString()}
                                </td>
                                <td>
                                  <button
                                      className="remove-button"
                                      onClick={() => handleRemoveWatchlist(item.watchlistId)}
                                  >
                                    Remove
                                  </button>
                                </td>
                              </tr>
                          ))}
                          </tbody>
                        </table>
                    ) : (
                        <p>No assets in your watchlist yet.</p>
                    )}

                  </div>
              )}

              {/* Profile */}
              {activeSection === 'profile' && profile && (
                  <div className="profile-section">

                    <h2>My Profile</h2>

                    <div className="profile-card">

                      <div className="profile-avatar">
                        {profile.profilePicture ? (
                            <img
                                src={`http://localhost:8081/${profile.profilePicture.replace(/\\/g, '/')}`}
                                alt="Profile"
                            />
                        ) : (
                            <>
                              {profile.firstName?.charAt(0)}
                              {profile.lastName?.charAt(0)}
                            </>
                        )}
                      </div>

                      <div className="profile-info">
                        <div>
                          <span>First Name</span>
                          <strong>{profile.firstName}</strong>
                        </div>

                        <div>
                          <span>Last Name</span>
                          <strong>{profile.lastName}</strong>
                        </div>

                        <div>
                          <span>Email Address</span>
                          <strong>{profile.emailAddress}</strong>
                        </div>

                        <div>
                          <span>Phone Number</span>
                          <strong>{profile.phoneNumber || 'Not provided'}</strong>
                        </div>
                      </div>

                    </div>

                    {/* EDIT BUTTON */}
                    <button
                        className="edit-profile-button"
                        onClick={() => {
                          setEditFirstName(profile.firstName || '')
                          setEditLastName(profile.lastName || '')
                          setEditPhoneNumber(profile.phoneNumber || '')
                          setIsEditingProfile(true)
                          setProfileMessage('')
                        }}
                    >
                      Edit Profile
                    </button>

                    <button
                        className="change-password-button"
                        onClick={() => {
                          setShowChangePassword(true)
                          setCurrentPassword('')
                          setChangeNewPassword('')
                          setChangePasswordMessage('')
                        }}
                    >
                      Change Password
                    </button>

                    {showChangePassword && (
                        <div className="edit-profile-form">
                          <h3>Change Password</h3>

                          {changePasswordMessage && (
                              <p
                                  className={
                                    changePasswordSuccess
                                        ? 'profile-success-message'
                                        : 'profile-message'
                                  }
                              >
                                {changePasswordMessage}
                              </p>
                          )}

                          <label>Current Password</label>
                          <input
                              type="password"
                              placeholder="Enter your current password"
                              value={currentPassword}
                              onChange={(e) => setCurrentPassword(e.target.value)}
                              autoComplete="current-password"
                          />

                          <label>New Password</label>
                          <input
                              type="password"
                              placeholder="Minimum 8 characters"
                              value={changeNewPassword}
                              onChange={(e) => setChangeNewPassword(e.target.value)}
                              autoComplete="new-password"
                          />

                          <div className="edit-profile-actions">
                            <button
                                className="save-profile-button"
                                onClick={handleChangePassword}
                            >
                              Change Password
                            </button>

                            <button
                                className="cancel-button"
                                onClick={() => {
                                  setShowChangePassword(false)
                                  setCurrentPassword('')
                                  setChangeNewPassword('')
                                  setChangePasswordMessage('')
                                }}
                            >
                              Cancel
                            </button>
                          </div>
                        </div>
                    )}

                    {isEditingProfile && (
                        <div className="edit-profile-form">

                          <h3>Edit Profile</h3>
                          {profileMessage && (
                              <p className="profile-message">
                                {profileMessage}
                              </p>
                          )}

                          <label>First Name</label>
                          <input
                              type="text"
                              value={editFirstName}
                              onChange={(e) => setEditFirstName(e.target.value)}
                          />

                          <label>Last Name</label>
                          <input
                              type="text"
                              value={editLastName}
                              onChange={(e) => setEditLastName(e.target.value)}
                          />

                          <label>Phone Number</label>
                          <input
                              type="text"
                              value={editPhoneNumber}
                              onChange={(e) => setEditPhoneNumber(e.target.value)}
                          />

                          <div className="edit-profile-actions">
                            <button
                                className="save-profile-button"
                                onClick={handleUpdateProfile}
                            >
                              Save Changes
                            </button>

                            <button
                                className="cancel-button"
                                onClick={() => setIsEditingProfile(false)}
                            >
                              Cancel
                            </button>
                          </div>

                        </div>
                    )}

                    {/* PROFILE PICTURE */}
                    <div className="profile-picture-section">
                      <h3>Profile Picture</h3>

                      <p>Choose an image to use as your profile picture.</p>

                      <input
                          type="file"
                          accept="image/*"
                          onChange={(e) => {
                            setProfilePictureFile(e.target.files[0])
                            setPictureMessage('')
                          }}
                      />

                      <button
                          className="upload-picture-button"
                          disabled={!profilePictureFile}
                          onClick={handleUploadProfilePicture}
                      >
                        Upload Picture
                      </button>

                      {pictureMessage && (
                          <p className="picture-message">{pictureMessage}</p>
                      )}
                    </div>


                  </div>
              )}

          </main>

          </div>
        </div>
    )
  }

  //login page
  return (
      <div className="login-page">

        <div className="login-card">

          <div className="login-logo">$</div>

          <h1>Virtual Investment Portfolio</h1>

          <p className="login-subtitle">
            Build and manage your investment portfolio using virtual money.
          </p>

          {authMode === 'login' && (
          <div className="login-form">

            <label>Email Address</label>
            <input
                type="email"
                placeholder="Enter your email"
                value={emailAddress}
                onChange={(e) => setEmailAddress(e.target.value)}
            />

            <label>Password</label>
            <input
                type="password"
                placeholder="Enter your password"
                value={password}
                onChange={(e) => setPassword(e.target.value)}
            />

            <button
                type="button"
                className="forgot-password-link"
                onClick={() => {
                  setAuthMode('forgot')
                  setForgotEmail('')
                  setForgotMessage('')
                  setMessage('')
                }}
            >
              Forgot Password?
            </button>

            <button
                className="login-button"
                onClick={handleLogin}
            >
              Login
            </button>

            {message && (
                <p className="login-message">{message}</p>
            )}

            <p className="auth-switch">
              Don't have an account?{' '}
              <button
                  type="button"
                  onClick={() => {
                    setAuthMode('register')
                    setMessage('')

                    setRegisterFirstName('')
                    setRegisterLastName('')
                    setRegisterPhone('')
                    setRegisterEmail('')
                    setRegisterPassword('')
                  }}
              >
                Register
              </button>
            </p>

          </div>
          )}

          {authMode === 'register' && (
              <div className="login-form">

                <label>First Name</label>
                <input
                    type="text"
                    placeholder="Enter your first name"
                    value={registerFirstName}
                    onChange={(e) => setRegisterFirstName(e.target.value)}
                />

                <label>Last Name</label>
                <input
                    type="text"
                    placeholder="Enter your last name"
                    value={registerLastName}
                    onChange={(e) => setRegisterLastName(e.target.value)}
                />

                <label>Phone Number</label>
                <input
                    type="text"
                    placeholder="Enter your 8-digit phone number"
                    value={registerPhone}
                    onChange={(e) => setRegisterPhone(e.target.value)}
                />

                <label>Email Address</label>
                <input
                    type="email"
                    placeholder="Enter your email"
                    value={registerEmail}
                    onChange={(e) => setRegisterEmail(e.target.value)}
                    autoComplete="email"
                />

                <label>Password</label>
                <input
                    type="password"
                    placeholder="Minimum 8 characters"
                    value={registerPassword}
                    onChange={(e) => setRegisterPassword(e.target.value)}
                    autoComplete="new-password"
                />

                <button
                    className="login-button"
                    onClick={handleRegister}
                >
                  Create Account
                </button>

                {registerMessage && (
                    <p className="login-message">{registerMessage}</p>
                )}

                <p className="auth-switch">
                  Already have an account?{' '}
                  <button
                      type="button"
                      onClick={() => {
                        setAuthMode('login')
                        setRegisterMessage('')

                      }}
                  >
                    Login
                  </button>
                </p>

              </div>
          )}

          {authMode === 'forgot' && (
              <div className="login-form">

                <p className="forgot-description">
                  Enter your email address and we'll send you a password reset link.
                </p>

                <label>Email Address</label>
                <input
                    type="email"
                    placeholder="Enter your email"
                    value={forgotEmail}
                    onChange={(e) => setForgotEmail(e.target.value)}
                    autoComplete="email"
                />

                <button
                    className="login-button"
                    onClick={handleForgotPassword}
                >
                  Send Reset Link
                </button>

                {forgotMessage && (
                    <p className="login-message">{forgotMessage}</p>
                )}

                <p className="auth-switch">
                  Remember your password?{' '}
                  <button
                      type="button"
                      onClick={() => {
                        setAuthMode('login')
                        setForgotMessage('')
                        setForgotEmail('')
                      }}
                  >
                    Back to Login
                  </button>
                </p>

              </div>
          )}

          {authMode === 'reset' && (
              <div className="login-form">

                <p className="forgot-description">
                  Enter your new password below.
                </p>

                <label>New Password</label>
                <input
                    type="password"
                    placeholder="Minimum 8 characters"
                    value={newPassword}
                    onChange={(e) => setNewPassword(e.target.value)}
                    autoComplete="new-password"
                />

                <button
                    className="login-button"
                    onClick={handleResetPassword}
                >
                  Reset Password
                </button>

                {resetMessage && (
                    <p className="login-message">{resetMessage}</p>
                )}

              </div>
          )}

          <p className="login-footer">
            Virtual Trading Platform
          </p>

        </div>

      </div>
  )

}
export default App