import { useEffect, useMemo, useState } from 'react'
import { Link, Navigate, NavLink, Route, Routes, useLocation, useNavigate, useParams } from 'react-router-dom'
import {
  Activity, ArrowLeft, ArrowRight, BarChart3,
  Check, ChevronDown, CircleHelp, ClipboardList, Clock3, Code2, Database, ExternalLink,
  FolderGit2, Gauge, GitPullRequest, KeyRound, Layers3, LogOut, Menu, Plus, RefreshCw, Search,
  Pencil, Shield, ShieldAlert, ShieldCheck, Trash2, X,
  UserRound, AlertTriangle, Info, Sparkles,
} from 'lucide-react'
import { api, clearToken, getToken, setToken } from './api.js'

function ErrorState({ message, onRetry }) {
  return <div className="state-panel error-panel" role="alert"><div className="state-icon"><ShieldAlert size={18} /></div><div><strong>Unable to load this data</strong><p>{message}</p>{onRetry && <button className="button button-secondary button-small" onClick={onRetry}><RefreshCw size={14} /> Retry</button>}</div></div>
}

function LoadingRows({ count = 4 }) {
  return <div className="skeleton-list" aria-label="Loading"><span className="sr-only">Loading data</span>{Array.from({ length: count }, (_, i) => <div className="skeleton-row" key={i}><i /><i /><i /></div>)}</div>
}

function EmptyState({ title, detail, action }) {
  return <div className="empty-state"><div className="empty-mark"><Layers3 size={20} /></div><strong>{title}</strong><p>{detail}</p>{action}</div>
}

function RiskBadge({ tier }) {
  const normalized = (tier || 'UNKNOWN').toLowerCase()
  return <span className={`risk-badge risk-${normalized}`}><span className="risk-dot" />{tier || 'UNASSESSED'}</span>
}

function Login({ onLogin }) {
  const [mode, setMode] = useState('login')
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)

  async function submit(event) {
    event.preventDefault()
    setError('')
    setBusy(true)
    try {
      const result = mode === 'login'
        ? await api.login(email.trim(), password)
        : await api.register(email.trim(), password)
      setToken(result.token)
      onLogin()
    } catch (e) {
      setError(e.message)
    } finally {
      setBusy(false)
    }
  }

  return <main className="login-shell">
    <section className="login-brand"><div className="brand-lockup"><span className="brand-symbol"><Shield size={17} /></span><span>endpoint<span className="brand-accent">guard</span></span></div>
      <div className="login-intro"><p className="eyebrow">TRAFFIC-AWARE CODE REVIEW</p><h1>Ship changes<br />with production<br /><em>context.</em></h1><p>Connect code changes to the endpoints, traffic, and reliability they can affect.</p></div>
      <div className="login-flow"><span><GitPullRequest size={15} /> Pull request</span><ArrowRight size={15} /><span><Activity size={15} /> Live signals</span><ArrowRight size={15} /><span><ShieldCheck size={15} /> Risk context</span></div>
      <div className="login-footnote"><span className="status-dot" /> API-connected risk intelligence</div>
    </section>
    <section className="login-form-wrap"><form className="login-form" onSubmit={submit}>
      <div className="form-heading"><span className="eyebrow">SECURE WORKSPACE</span><h2>{mode === 'login' ? 'Sign in' : 'Create account'}</h2><p>{mode === 'login' ? 'Use your EndpointGuard account credentials.' : 'Create an account to start configuring your workspace.'}</p></div>
      {error && <div className="inline-error" role="alert">{error}</div>}
      <label htmlFor="email">Work email</label><input id="email" type="email" autoComplete="username" required value={email} onChange={e => setEmail(e.target.value)} placeholder="you@company.com" />
      <label htmlFor="password">Password</label><input id="password" type="password" autoComplete={mode === 'login' ? 'current-password' : 'new-password'} minLength={mode === 'login' ? undefined : 8} required value={password} onChange={e => setPassword(e.target.value)} placeholder={mode === 'login' ? 'Enter your password' : 'At least 8 characters'} />
      <button className="button button-primary login-submit" disabled={busy}>{busy ? <span className="spinner" /> : null}{busy ? 'Please wait…' : mode === 'login' ? 'Sign in' : 'Create account'}<ArrowRight size={16} /></button>
      <p className="auth-switch">{mode === 'login' ? 'New to EndpointGuard?' : 'Already have an account?'} <button type="button" onClick={() => { setMode(mode === 'login' ? 'register' : 'login'); setError('') }}>{mode === 'login' ? 'Create an account' : 'Sign in'}</button></p>
      <p className="login-security"><ShieldCheck size={14} /> Your session is secured with JWT authentication.</p>
    </form></section>
  </main>
}

function AppShell({ user, onLogout, projects, activeProject, setActiveProject, endpointCount, apiStatus, children }) {
  const [mobileMenu, setMobileMenu] = useState(false)
  const location = useLocation()
  const title = location.pathname.startsWith('/endpoints/') ? 'Endpoint detail'
    : location.pathname === '/endpoints' ? 'Endpoints'
    : location.pathname === '/projects' ? 'Projects & repositories'
    : location.pathname === '/monitoring' ? 'Post-merge monitoring'
    : location.pathname === '/audit-logs' ? 'Audit logs'
    : location.pathname === '/profile' ? 'Profile & security'
    : location.pathname === '/pull-requests' ? 'Pull requests'
    : location.pathname.startsWith('/pull-requests/') ? 'Pull request detail' : 'Overview'
  const navItems = [
    { to: '/', label: 'Overview', icon: BarChart3, end: true },
    { to: '/projects', label: 'Projects', icon: FolderGit2 },
    { to: '/pull-requests', label: 'Pull requests', icon: GitPullRequest },
    { to: '/endpoints', label: 'Endpoints', icon: Code2 },
    { to: '/monitoring', label: 'Monitoring', icon: Activity },
    { to: '/audit-logs', label: 'Audit logs', icon: ClipboardList },
  ]
  return <div className="app-shell">
    <aside className={`sidebar ${mobileMenu ? 'sidebar-open' : ''}`}>
      <Link to="/" className="brand-lockup" onClick={() => setMobileMenu(false)}><span className="brand-symbol"><Shield size={16} /></span><span>endpoint<span className="brand-accent">guard</span></span></Link>
      <label className="workspace-select"><span className="workspace-avatar">{activeProject?.name?.slice(0, 1)?.toUpperCase() || 'E'}</span><span className="workspace-copy"><small>WORKSPACE</small><select className="workspace-picker" value={activeProject?.id || ''} aria-label="Select project" onChange={event => { const project = projects.find(item => String(item.id) === event.target.value); if (project) setActiveProject(project) }}><option value="" disabled>{projects.length ? 'Select a project' : 'No projects yet'}</option>{projects.map(project => <option key={project.id} value={project.id}>{project.name}</option>)}</select></span><ChevronDown size={14} aria-hidden="true" /></label>
      <span className="nav-caption">WORKSPACE</span>
      <nav className="primary-nav" aria-label="Main navigation">{navItems.map(({ to, label, icon: Icon, end }) => <NavLink key={to} to={to} end={end} onClick={() => setMobileMenu(false)} className={({ isActive }) => `nav-link ${isActive ? 'nav-active' : ''}`}><Icon size={17} strokeWidth={1.8} /><span>{label}</span>{label === 'Endpoints' && <span className="nav-count">{endpointCount}</span>}</NavLink>)}</nav>
      <div className="sidebar-system"><span className="nav-caption">SYSTEM STATUS</span><div className="system-line"><span className={`status-dot status-${apiStatus}`} /><span>{apiStatus === 'online' ? 'API connected' : apiStatus === 'checking' ? 'Checking API' : 'API unavailable'}</span><span className="system-live">{apiStatus === 'online' ? 'LIVE' : apiStatus === 'checking' ? 'CHECK' : 'OFFLINE'}</span></div><p>Traffic snapshots update every 60 seconds.</p></div>
      <div className="sidebar-bottom"><a className="help-link" href="http://localhost:8080/swagger-ui/index.html" target="_blank" rel="noreferrer"><CircleHelp size={16} /> API reference <ExternalLink size={12} /></a><div className="user-menu"><span className="user-avatar">{user?.slice(0, 1).toUpperCase() || 'U'}</span><div className="user-copy"><strong>{user || 'Signed in'}</strong><small>Workspace member</small></div><Link className="icon-button" to="/profile" aria-label="Profile and security" title="Profile and security"><UserRound size={15} /></Link><button className="icon-button logout-button" onClick={onLogout} aria-label="Sign out" title="Sign out"><LogOut size={16} /></button></div></div>
    </aside>
    {mobileMenu && <button className="mobile-scrim" aria-label="Close navigation" onClick={() => setMobileMenu(false)} />}
    <div className="main-frame"><header className="topbar"><button className="icon-button mobile-menu-button" onClick={() => setMobileMenu(!mobileMenu)} aria-label="Toggle navigation"><Menu size={19} /></button><div className="breadcrumbs"><span>EndpointGuard</span><span className="crumb-slash">/</span><strong>{title}</strong></div><div className="topbar-right"><span className="api-pill"><span className={`status-dot status-${apiStatus}`} />{apiStatus === 'online' ? 'API operational' : apiStatus === 'checking' ? 'Checking API' : 'API unavailable'}</span><div className="topbar-user">{user?.slice(0, 1).toUpperCase()}</div></div></header><main className="main-content">{children}</main></div>
  </div>
}

function Dashboard({ projects, repositories, endpoints, refresh, loading, error }) {
  const [search, setSearch] = useState('')
  const [rollup, setRollup] = useState([])
  const [metricsLoading, setMetricsLoading] = useState(false)
  const [metricsError, setMetricsError] = useState('')

  useEffect(() => {
    let cancelled = false
    if (!endpoints.length) { setRollup([]); return undefined }
    setMetricsLoading(true)
    setMetricsError('')
    Promise.all(endpoints.map(async endpoint => {
      try {
        const metrics = await api.endpointMetrics(endpoint.id, localDateHoursAgo(24), localDateNow())
        return { endpoint, metrics: Array.isArray(metrics) ? metrics : [] }
      } catch (e) {
        throw e
      }
    })).then(rows => { if (!cancelled) setRollup(rows) }).catch(e => { if (!cancelled) setMetricsError(e.message) }).finally(() => { if (!cancelled) setMetricsLoading(false) })
    return () => { cancelled = true }
  }, [endpoints])

  const filteredEndpoints = useMemo(() => endpoints.filter(endpoint => `${endpoint.method} ${endpoint.pathPattern} ${endpoint.criticality}`.toLowerCase().includes(search.toLowerCase())), [endpoints, search])
  const requestTotal = rollup.flatMap(row => row.metrics).reduce((sum, metric) => sum + (metric.requestCount || 0), 0)
  const highCritical = endpoints.filter(endpoint => ['HIGH', 'CRITICAL'].includes(endpoint.criticality)).length
  const trendData = useMemo(() => {
    const buckets = new Map()
    rollup.flatMap(row => row.metrics).forEach(metric => {
      const bucketStart = metric.bucketStart || '—'
      buckets.set(bucketStart, (buckets.get(bucketStart) || 0) + (metric.requestCount || 0))
    })
    return [...buckets.entries()].sort(([left], [right]) => left.localeCompare(right)).slice(-12).map(([bucketStart, requests]) => ({ time: formatTimeInIst(bucketStart), requests }))
  }, [rollup])

  return <>
    <PageHeading eyebrow="ENGINEERING OVERVIEW" title="Production risk overview" subtitle="A live view of registered API surfaces and their recent traffic signals." action={<button className="button button-secondary" onClick={refresh}><RefreshCw size={14} /> Refresh data</button>} />
    {error && <ErrorState message={error} onRetry={refresh} />}
    <div className="scope-line"><span className="scope-icon"><FolderGit2 size={15} /></span><span>{projects.length} projects</span><span className="scope-sep">/</span><span>{repositories.length} linked repositories</span><span className="scope-sep">/</span><span>{endpoints.length} registered endpoints</span><span className="scope-right"><Clock3 size={13} /> Rolling 24 hours</span></div>
    <section className="metric-strip" aria-label="Engineering summary">
      <MetricTile label="Registered endpoints" value={loading ? '—' : endpoints.length} note="Across linked repositories" icon={Code2} />
      <MetricTile label="Requests observed" value={metricsLoading ? '…' : formatNumber(requestTotal)} note="Persisted metric buckets · 24h" icon={Activity} />
      <MetricTile label="High criticality" value={loading ? '—' : highCritical} note="HIGH or CRITICAL endpoints" icon={ShieldAlert} accent="amber" />
      <MetricTile label="Data source" value="LIVE API" note="No demo records injected" icon={Database} accent="green" compact />
    </section>
    {metricsError && <div className="subtle-warning"><ShieldAlert size={14} /> Traffic metrics could not be loaded: {metricsError}<button className="text-button" onClick={refresh}>Retry metrics</button></div>}
    <div className="dashboard-grid">
      <section className="panel traffic-panel"><div className="panel-heading"><div><span className="eyebrow">OBSERVABILITY</span><h2>Traffic volume</h2></div><span className="chart-legend"><i /> Requests</span></div>
        {metricsLoading ? <LoadingRows count={3} /> : trendData.length ? <div className="chart-wrap"><TrendChart data={trendData} dataKey="requests" color="#50c9ae" label="Requests per metric bucket" /></div> : <EmptyState title="No traffic snapshots yet" detail="Metrics appear after an endpoint is registered and the poller has collected a matching traffic bucket." action={<Link className="text-link" to="/endpoints">View endpoint catalog <ArrowRight size={14} /></Link>} />}
        <div className="chart-footer"><span>Source: stored endpoint metrics</span><span>Requests per bucket</span></div>
      </section>
      <section className="panel signal-panel"><div className="panel-heading"><div><span className="eyebrow">RISK SURFACE</span><h2>Criticality distribution</h2></div><span className="info-tip" tabIndex="0" aria-label="Criticality is assigned when an endpoint is registered" data-tip="Criticality is assigned during endpoint registration; it is not the calculated Pull Request risk tier."><CircleHelp size={15} /></span></div>
        {loading ? <LoadingRows count={4} /> : endpoints.length ? <div className="distribution">{['CRITICAL', 'HIGH', 'MEDIUM', 'LOW'].map(tier => { const count = endpoints.filter(endpoint => endpoint.criticality === tier).length; const width = Math.max(count ? (count / endpoints.length) * 100 : 0, count ? 6 : 0); return <div className="distribution-row" key={tier}><div className="distribution-label"><span className={`criticality-marker marker-${tier.toLowerCase()}`} />{tier}<strong>{count}</strong></div><div className="distribution-track"><i className={`distribution-fill fill-${tier.toLowerCase()}`} style={{ width: `${width}%` }} /></div></div> })}<div className="distribution-foot">{endpoints.length} endpoints registered across {repositories.length} repositories</div></div> : <EmptyState title="No endpoint inventory" detail="Link a repository and register source patterns to map code to API routes." action={<Link className="text-link" to="/projects">Set up a repository <ArrowRight size={14} /></Link>} />}
      </section>
    </div>
    <section className="panel endpoint-preview"><div className="panel-heading endpoint-table-heading"><div><span className="eyebrow">INVENTORY</span><h2>Endpoint catalog <span className="count-bubble">{endpoints.length}</span></h2></div><div className="table-actions"><label className="search-box"><Search size={14} /><input value={search} onChange={e => setSearch(e.target.value)} placeholder="Filter endpoints" aria-label="Filter endpoints" /></label><Link className="button button-secondary button-small" to="/endpoints">View all <ArrowRight size={14} /></Link></div></div>
      {loading ? <LoadingRows /> : endpoints.length ? <div className="table-scroll"><table><thead><tr><th>Endpoint</th><th>Criticality</th><th>Source mapping</th><th>Project</th><th aria-label="Actions" /></tr></thead><tbody>{filteredEndpoints.slice(0, 6).map(endpoint => <tr key={endpoint.id}><td><Link className="endpoint-name" to={`/endpoints/${endpoint.id}`}><span className={`method method-${endpoint.method.toLowerCase()}`}>{endpoint.method}</span><code>{endpoint.pathPattern}</code></Link></td><td><CriticalityPill value={endpoint.criticality} /></td><td className="muted-cell">{endpoint.sourcePatterns?.[0] || 'No source pattern'}{endpoint.sourcePatterns?.length > 1 && <span className="more-count"> +{endpoint.sourcePatterns.length - 1}</span>}</td><td className="muted-cell">{projects.find(p => p.id === endpoint.projectId)?.name || '—'}</td><td><Link to={`/endpoints/${endpoint.id}`} className="row-arrow" aria-label={`Open ${endpoint.pathPattern}`}><ArrowRight size={15} /></Link></td></tr>)}</tbody></table>{!filteredEndpoints.length && <p className="table-empty">No endpoints match “{search}”.</p>}</div> : <EmptyState title="Your API surface starts here" detail="Create a project, link its GitHub repository, then register endpoint paths and source mappings." action={<Link className="button button-primary button-small" to="/projects"><Plus size={14} /> Add a project</Link>} />}
    </section>
    <section className="capability-note"><div className="capability-icon"><GitPullRequest size={16} /></div><div><strong>PR ingestion is configured; PR read views are not exposed yet.</strong><p>The backend accepts GitHub webhook events and maps changed files to endpoints. It currently has no Pull Request listing, risk history, monitoring query, risk-rules, or audit-log read endpoints, so those views are intentionally not populated with sample data.</p></div></section>
  </>
}

function MetricTile({ label, value, note, icon: Icon, accent, compact }) {
  return <article className="metric-tile"><div className="metric-tile-top"><span>{label}</span><span className={`tile-icon ${accent || ''}`}><Icon size={16} /></span></div><strong className={compact ? 'metric-compact' : ''}>{value}</strong><small>{note}</small></article>
}

function PageHeading({ eyebrow, title, subtitle, action }) {
  return <div className="page-heading"><div><span className="eyebrow">{eyebrow}</span><h1>{title}</h1>{subtitle && <p>{subtitle}</p>}</div>{action && <div className="heading-action">{action}</div>}</div>
}

function CriticalityPill({ value }) {
  return <span className={`criticality-pill criticality-${(value || 'medium').toLowerCase()}`}><span />{value || 'MEDIUM'}</span>
}

function mappingSuggestions(pathPattern, repositoryId, endpoints) {
  const resource = pathPattern.split('/').filter(segment => segment && !segment.startsWith('{') && !segment.startsWith(':')).at(-1)?.toLowerCase()
  if (!resource) return []
  const singularResource = resource.replace(/s$/, '')
  const existing = [...new Set(endpoints
    .filter(endpoint => String(endpoint.repositoryId) === String(repositoryId)
      && endpoint.pathPattern.split('/').filter(segment => segment && !segment.startsWith('{') && !segment.startsWith(':')).at(-1)?.toLowerCase().replace(/s$/, '') === singularResource)
    .flatMap(endpoint => endpoint.sourcePatterns || []))]
  if (existing.length) return existing
  const className = singularResource.split(/[-_]/).map(part => part.charAt(0).toUpperCase() + part.slice(1)).join('')
  return [`**/*${className}*Controller.*`, `**/*${className}*Service.*`]
}

function ProjectsPage({ projects, repositories, refresh, loading, error, activeProject, setActiveProject }) {
  const [projectName, setProjectName] = useState('')
  const [repoName, setRepoName] = useState('')
  const [secretRef, setSecretRef] = useState('')
  const [busy, setBusy] = useState(false)
  const [notice, setNotice] = useState('')
  const [formError, setFormError] = useState('')
  const [repositoryEdit, setRepositoryEdit] = useState(null)

  async function createProject(event) {
    event.preventDefault()
    setBusy(true); setFormError(''); setNotice('')
    try { const project = await api.createProject(projectName.trim()); setProjectName(''); await refresh(project.id); setNotice(`Project “${project.name}” created.`) }
    catch (e) { setFormError(e.message) }
    finally { setBusy(false) }
  }
  async function linkRepo(event) {
    event.preventDefault()
    if (!activeProject) return
    setBusy(true); setFormError(''); setNotice('')
    try {
      if (repositoryEdit) await api.updateRepository(activeProject.id, repositoryEdit, repoName.trim(), secretRef.trim())
      else await api.linkRepository(activeProject.id, repoName.trim(), secretRef.trim())
      setRepoName(''); setSecretRef(''); setRepositoryEdit(null); await refresh(activeProject.id); setNotice(repositoryEdit ? 'Repository updated.' : 'Repository linked successfully.')
    }
    catch (e) { setFormError(e.message) }
    finally { setBusy(false) }
  }

  async function deleteRepository(repository) {
    if (!activeProject || !window.confirm(`Delete ${repository.githubRepoFullName}? Its endpoints, pull requests, metrics, and risk history will also be deleted.`)) return
    setBusy(true); setFormError(''); setNotice('')
    try { await api.deleteRepository(activeProject.id, repository.id); await refresh(activeProject.id); setNotice('Repository and its linked data deleted.') }
    catch (e) { setFormError(e.message) }
    finally { setBusy(false) }
  }

  return <>
    <PageHeading eyebrow="WORKSPACE CONFIGURATION" title="Projects & repositories" subtitle="Connect the codebases whose API surfaces and traffic you want to understand." />
    {error && <ErrorState message={error} onRetry={refresh} />}{notice && <div className="inline-success"><Check size={15} />{notice}<button className="icon-button" onClick={() => setNotice('')} aria-label="Dismiss"><X size={14} /></button></div>}{formError && <div className="inline-error" role="alert">{formError}</div>}
    <div className="setup-grid"><section className="panel setup-panel"><div className="panel-heading"><div><span className="eyebrow">STEP 01</span><h2>Create a project</h2></div><span className="setup-step"><FolderGit2 size={17} /></span></div><p className="panel-description">A project groups the repositories and API endpoints that belong to one service or product area.</p><form onSubmit={createProject} className="stack-form"><label htmlFor="project-name">Project name</label><input id="project-name" value={projectName} onChange={e => setProjectName(e.target.value)} required maxLength={255} placeholder="e.g. Payments platform" /><button className="button button-primary" disabled={busy}><Plus size={15} /> Create project</button></form></section>
      <section className="panel setup-panel"><div className="panel-heading"><div><span className="eyebrow">STEP 02</span><h2>{repositoryEdit ? 'Edit repository' : 'Link a repository'}</h2></div><span className="setup-step"><GitPullRequest size={17} /></span></div><p className="panel-description">Link the GitHub repository that emits PR webhooks. The secret reference is stored as a label; configure actual webhook verification separately.</p><form onSubmit={linkRepo} className="stack-form"><label htmlFor="project-select">Project</label><select id="project-select" value={activeProject?.id || ''} onChange={e => setActiveProject(projects.find(p => String(p.id) === e.target.value) || null)} required><option value="" disabled>Select a project</option>{projects.map(project => <option key={project.id} value={project.id}>{project.name}</option>)}</select><label htmlFor="repo-name">GitHub repository</label><input id="repo-name" value={repoName} onChange={e => setRepoName(e.target.value)} required placeholder="owner/repository" /><label htmlFor="secret-ref">Webhook secret reference</label><input id="secret-ref" value={secretRef} onChange={e => setSecretRef(e.target.value)} required placeholder="e.g. GITHUB_WEBHOOK_SECRET" /><div className="form-actions"><button className="button button-primary" disabled={busy || !activeProject}>{repositoryEdit ? <><Check size={15} /> Save repository</> : <><Plus size={15} /> Link repository</>}</button>{repositoryEdit && <button type="button" className="button button-secondary" onClick={() => { setRepositoryEdit(null); setRepoName(''); setSecretRef('') }}>Cancel</button>}</div></form></section></div>
    <section className="panel"><div className="panel-heading"><div><span className="eyebrow">CONNECTED SOURCES</span><h2>Repositories <span className="count-bubble">{repositories.length}</span></h2></div></div>
      {loading ? <LoadingRows /> : repositories.length ? <div className="repo-list">{repositories.map(repo => <article className="repo-row" key={repo.id}><span className="repo-icon"><FolderGit2 size={17} /></span><div className="repo-main"><strong>{repo.githubRepoFullName}</strong><small>Linked {formatDate(repo.installedAt)}</small></div><span className="repo-project">{projects.find(p => p.id === repo.projectId)?.name || 'Project'}</span><span className="repo-status"><span className="status-dot" /> Connected</span><div className="row-actions"><button className="icon-button" disabled={busy} aria-label={`Edit ${repo.githubRepoFullName}`} title="Edit repository" onClick={() => { setRepositoryEdit(repo.id); setRepoName(repo.githubRepoFullName); setSecretRef(repo.webhookSecretRef); setFormError('') }}><Pencil size={14} /></button><button className="icon-button destructive-action" disabled={busy} aria-label={`Delete ${repo.githubRepoFullName}`} title="Delete repository" onClick={() => deleteRepository(repo)}><Trash2 size={14} /></button></div></article>)}</div> : <EmptyState title="No repositories linked" detail="Create or select a project, then link its GitHub repository to start registering endpoint source mappings." />}
    </section>
  </>
}

function EndpointsPage({ endpoints, projects, repositories, refresh, loading, error }) {
  const [filter, setFilter] = useState('ALL')
  const [query, setQuery] = useState('')
  const [showForm, setShowForm] = useState(false)
  const [repoId, setRepoId] = useState(repositories[0]?.id || '')
  const [form, setForm] = useState({ method: 'GET', pathPattern: '', criticality: 'MEDIUM', sourcePatterns: '' })
  const [formError, setFormError] = useState('')
  const [saving, setSaving] = useState(false)
  const [editingEndpointId, setEditingEndpointId] = useState(null)

  useEffect(() => { if (!repositories.some(repo => String(repo.id) === String(repoId))) setRepoId(repositories[0]?.id || '') }, [repositories, repoId])
  const visible = endpoints.filter(endpoint => (filter === 'ALL' || endpoint.criticality === filter) && `${endpoint.method} ${endpoint.pathPattern} ${endpoint.sourcePatterns?.join(' ')}`.toLowerCase().includes(query.toLowerCase()))

  async function submit(event) {
    event.preventDefault(); setSaving(true); setFormError('')
    try {
      const payload = { ...form, sourcePatterns: form.sourcePatterns.split(/\r?\n|,/).map(item => item.trim()).filter(Boolean) }
      if (editingEndpointId) await api.updateEndpoint(repoId, editingEndpointId, payload)
      else await api.registerEndpoint(repoId, payload)
      setShowForm(false); setEditingEndpointId(null); setForm({ method: 'GET', pathPattern: '', criticality: 'MEDIUM', sourcePatterns: '' }); await refresh()
    }
    catch (e) { setFormError(e.message) }
    finally { setSaving(false) }
  }

  function editEndpoint(endpoint) {
    setEditingEndpointId(endpoint.id); setRepoId(endpoint.repositoryId)
    setForm({ method: endpoint.method, pathPattern: endpoint.pathPattern, criticality: endpoint.criticality, sourcePatterns: (endpoint.sourcePatterns || []).join('\n') })
    setFormError(''); setShowForm(true)
  }

  async function deleteEndpoint(endpoint) {
    if (!window.confirm(`Delete ${endpoint.method} ${endpoint.pathPattern}? Its source mappings, traffic metrics, and linked PR risk data will also be deleted.`)) return
    setSaving(true); setFormError('')
    try { await api.deleteEndpoint(endpoint.repositoryId, endpoint.id); await refresh() }
    catch (e) { setFormError(e.message) }
    finally { setSaving(false) }
  }

  const suggestions = mappingSuggestions(form.pathPattern, repoId, endpoints)

  return <>
    <PageHeading eyebrow="PRODUCTION SURFACE" title="Endpoints" subtitle="Registered API routes mapped to their source files and current traffic evidence." action={<button className="button button-primary" onClick={() => setShowForm(!showForm)} disabled={!repositories.length}><Plus size={15} /> Register endpoint</button>} />
    {error && <ErrorState message={error} onRetry={refresh} />}
    {showForm && <section className="panel endpoint-form-panel"><div className="panel-heading"><div><span className="eyebrow">ENDPOINT CONFIGURATION</span><h2>{editingEndpointId ? 'Edit endpoint' : 'Map an API route'}</h2></div><button className="icon-button" onClick={() => { setShowForm(false); setEditingEndpointId(null) }} aria-label="Close form"><X size={17} /></button></div>{formError && <div className="inline-error" role="alert">{formError}</div>}<form className="endpoint-form" onSubmit={submit}><label>Repository<select value={repoId} onChange={e => setRepoId(e.target.value)} required>{repositories.map(repo => <option key={repo.id} value={repo.id}>{repo.githubRepoFullName}</option>)}</select></label><label>Method<select value={form.method} onChange={e => setForm({ ...form, method: e.target.value })}>{['GET', 'POST', 'PUT', 'PATCH', 'DELETE'].map(method => <option key={method}>{method}</option>)}</select></label><label>Path pattern<input value={form.pathPattern} onChange={e => setForm({ ...form, pathPattern: e.target.value })} required placeholder="/api/orders/{id}" /></label><label>Criticality <span className="info-tip" tabIndex="0" aria-label="Criticality meaning" data-tip="Business impact if this route is affected. It is separate from the calculated PR risk tier."><CircleHelp size={12} /></span><select value={form.criticality} onChange={e => setForm({ ...form, criticality: e.target.value })}>{['LOW', 'MEDIUM', 'HIGH', 'CRITICAL'].map(level => <option key={level}>{level}</option>)}</select></label><label className="form-wide">Source patterns <span className="form-hint">Patterns connect changed files to this endpoint. Suggestions can be edited or replaced.</span><div className="suggestion-list" aria-label="Suggested source patterns">{suggestions.map(pattern => <button type="button" className="suggestion-chip" key={pattern} onClick={() => setForm(current => ({ ...current, sourcePatterns: [...new Set([...current.sourcePatterns.split(/\r?\n|,/).map(item => item.trim()).filter(Boolean), pattern])].join('\n') }))}>{pattern}<Plus size={12} /></button>)}</div><textarea rows="3" value={form.sourcePatterns} onChange={e => setForm({ ...form, sourcePatterns: e.target.value })} required placeholder="Enter one source glob per line" /></label><div className="form-wide form-footer"><span>Criticality describes business impact; source mappings determine which code changes affect the route.</span><div className="form-actions"><button className="button button-primary" disabled={saving}>{saving ? 'Saving…' : editingEndpointId ? 'Save endpoint' : 'Register endpoint'}</button>{editingEndpointId && <button type="button" className="button button-secondary" onClick={() => { setShowForm(false); setEditingEndpointId(null) }}>Cancel</button>}</div></div></form></section>}
    <div className="catalog-toolbar"><div className="segment-control" role="group" aria-label="Filter by criticality">{['ALL', 'CRITICAL', 'HIGH', 'MEDIUM', 'LOW'].map(level => <button key={level} className={filter === level ? 'segment-active' : ''} onClick={() => setFilter(level)}>{level === 'ALL' ? 'All' : level[0] + level.slice(1).toLowerCase()}</button>)}</div><label className="search-box"><Search size={14} /><input value={query} onChange={e => setQuery(e.target.value)} placeholder="Search endpoint or source" aria-label="Search endpoints" /></label></div>
    {formError && !showForm && <div className="inline-error" role="alert">{formError}</div>}
    {loading ? <section className="panel"><LoadingRows count={6} /></section> : visible.length ? <section className="endpoint-cards">{visible.map(endpoint => <article className="endpoint-card" key={endpoint.id}><Link to={`/endpoints/${endpoint.id}`}><div className="endpoint-card-top"><span className={`method method-${endpoint.method.toLowerCase()}`}>{endpoint.method}</span><CriticalityPill value={endpoint.criticality} /></div><code className="endpoint-path">{endpoint.pathPattern}</code><div className="endpoint-card-meta"><span>{projects.find(p => p.id === endpoint.projectId)?.name || 'Project'}</span><span>{endpoint.sourcePatterns?.length || 0} source mappings</span></div><div className="endpoint-card-foot"><span><Activity size={13} /> Traffic data</span><ArrowRight size={15} /></div></Link><div className="endpoint-card-actions"><button className="icon-button" disabled={saving} aria-label={`Edit ${endpoint.method} ${endpoint.pathPattern}`} title="Edit endpoint" onClick={() => editEndpoint(endpoint)}><Pencil size={14} /></button><button className="icon-button destructive-action" disabled={saving} aria-label={`Delete ${endpoint.method} ${endpoint.pathPattern}`} title="Delete endpoint" onClick={() => deleteEndpoint(endpoint)}><Trash2 size={14} /></button></div></article>)}</section> : <section className="panel"><EmptyState title={query ? 'No matching endpoints' : 'No endpoints registered'} detail={query ? 'Try another route, source file, or criticality filter.' : repositories.length ? 'Register a route with source patterns to start measuring its production behavior.' : 'Link a GitHub repository before registering API endpoints.'} action={!query && repositories.length ? <button className="button button-primary button-small" onClick={() => setShowForm(true)}><Plus size={14} /> Register endpoint</button> : null} /></section>}
  </>
}

function EndpointDetail({ endpoints, projects, repositories }) {
  const { endpointId } = useParams()
  const endpoint = endpoints.find(item => String(item.id) === endpointId)
  const [metrics, setMetrics] = useState([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [range, setRange] = useState(24)
  const navigate = useNavigate()

  async function loadMetrics() {
    if (!endpoint) return
    setLoading(true); setError('')
    try { const data = await api.endpointMetrics(endpoint.id, localDateHoursAgo(range), localDateNow()); setMetrics(data || []) }
    catch (e) { setError(e.message) }
    finally { setLoading(false) }
  }
  useEffect(() => { loadMetrics() }, [endpoint?.id, range])
  if (!endpoint) return <><button className="back-link" onClick={() => navigate('/endpoints')}><ArrowLeft size={14} /> Endpoint catalog</button><EmptyState title="Endpoint not found" detail="It may have been removed or belong to another project." action={<Link className="text-link" to="/endpoints">Return to endpoints</Link>} /></>

  const totalRequests = metrics.reduce((sum, metric) => sum + (metric.requestCount || 0), 0)
  const errorsTotal = metrics.reduce((sum, metric) => sum + (metric.error4xxCount || 0) + (metric.error5xxCount || 0), 0)
  const avgLatency = metrics.length ? metrics.reduce((sum, metric) => sum + (metric.avgLatencyMs || 0), 0) / metrics.length : null
  const latest = metrics[metrics.length - 1]
  const errorRate = totalRequests ? (errorsTotal / totalRequests) * 100 : null
  const chartData = metrics.map(metric => ({ time: formatTimeInIst(metric.bucketStart), requests: metric.requestCount || 0, latency: metric.avgLatencyMs || 0, errors: (metric.error4xxCount || 0) + (metric.error5xxCount || 0) }))
  const repo = repositories.find(item => item.projectId === endpoint.projectId)

  return <>
    <button className="back-link" onClick={() => navigate('/endpoints')}><ArrowLeft size={14} /> Endpoint catalog</button>
    <PageHeading eyebrow={`${projects.find(p => p.id === endpoint.projectId)?.name || 'PROJECT'} · ${repo?.githubRepoFullName || 'REPOSITORY'}`} title={<span className="detail-title"><span className={`method method-${endpoint.method.toLowerCase()}`}>{endpoint.method}</span><code>{endpoint.pathPattern}</code></span>} subtitle={`Source: ${endpoint.sourcePatterns?.join(', ') || 'No source patterns mapped'}`} action={<CriticalityPill value={endpoint.criticality} />} />
    <div className="detail-explain"><Activity size={15} /><span>Open pull-request webhooks automatically evaluate this endpoint using changed-file size and recent/baseline traffic. Missing metric samples are marked as insufficient data in the pull-request risk history.</span></div>
    <section className="endpoint-kpis"><MetricTile label={`Requests · ${range}h`} value={formatNumber(totalRequests)} note={`${metrics.length} stored buckets`} icon={Activity} /><MetricTile label="Error rate" value={errorRate == null ? '—' : `${errorRate.toFixed(2)}%`} note={`${formatNumber(errorsTotal)} 4xx / 5xx responses`} icon={ShieldAlert} accent={errorRate > 5 ? 'amber' : 'green'} /><MetricTile label="Average latency" value={avgLatency == null ? '—' : `${Math.round(avgLatency)} ms`} note="Mean across returned buckets" icon={Gauge} /><MetricTile label="Rate limit use" value={latest?.rateLimitUtilizationPct == null ? '—' : `${latest.rateLimitUtilizationPct.toFixed(1)}%`} note="Latest stored bucket" icon={BarChart3} /></section>
    <section className="panel metrics-panel"><div className="panel-heading"><div><span className="eyebrow">TRAFFIC & HEALTH</span><h2>Endpoint signals</h2></div><div className="segment-control range-control" role="group" aria-label="Metrics time range">{[1, 6, 24, 168].map(hours => <button className={range === hours ? 'segment-active' : ''} key={hours} onClick={() => setRange(hours)}>{hours === 168 ? '7D' : `${hours}H`}</button>)}</div></div>
      {loading ? <LoadingRows count={4} /> : error ? <ErrorState message={error} onRetry={loadMetrics} /> : metrics.length ? <div className="signal-charts"><SignalChart title="Requests" unit="req" data={chartData} dataKey="requests" color="#50c9ae" /><SignalChart title="Average latency" unit="ms" data={chartData} dataKey="latency" color="#8ba9df" /><SignalChart title="Error responses" unit="4xx + 5xx" data={chartData} dataKey="errors" color="#d68b5d" /></div> : <EmptyState title="No metrics in this time range" detail="Metrics are written when demo API traffic matches the method and path registered for this endpoint. Keep the traffic generator running and try again." action={<button className="button button-secondary button-small" onClick={loadMetrics}><RefreshCw size={14} /> Retry</button>} />}
    </section>
    <section className="panel risk-evaluate-panel"><div className="panel-heading"><div><span className="eyebrow">AUTOMATIC RISK</span><h2>PR assessments</h2></div><ShieldCheck size={17} /></div><p className="panel-description">Risk is recalculated from webhook file changes and stored traffic windows. Open the pull-request view to inspect scores, factors, and data-quality notes.</p><Link className="button button-secondary" to="/pull-requests"><GitPullRequest size={15} /> View pull requests <ArrowRight size={14} /></Link></section>
  </>
}

function TrendChart({ data, dataKey, color, label }) {
  const width = 600
  const height = 210
  const left = 42
  const right = 10
  const top = 14
  const bottom = 30
  const plotWidth = width - left - right
  const plotHeight = height - top - bottom
  const values = data.map(item => Number(item[dataKey]) || 0)
  const maximum = Math.max(...values, 1)
  const coordinates = values.map((value, index) => ({
    x: left + (values.length < 2 ? plotWidth / 2 : (index / (values.length - 1)) * plotWidth),
    y: top + plotHeight - (value / maximum) * plotHeight,
    value,
    time: data[index]?.time || '',
  }))
  const points = coordinates.map(point => `${point.x},${point.y}`).join(' ')
  const areaPoints = `${left},${top + plotHeight} ${points} ${left + plotWidth},${top + plotHeight}`
  const tickStep = Math.max(1, Math.ceil((data.length - 1) / 4))
  const timeTicks = data.filter((_, index) => index % tickStep === 0)
  if (data.length > 1 && timeTicks[timeTicks.length - 1] !== data[data.length - 1]) timeTicks.push(data[data.length - 1])

  return <svg className="line-chart-svg" viewBox={`0 0 ${width} ${height}`} preserveAspectRatio="none" role="img" aria-label={label}>
    <title>{label}</title>
    {[0, 0.5, 1].map(ratio => {
      const y = top + plotHeight * ratio
      const tickValue = Math.round(maximum * (1 - ratio))
      return <g key={ratio}><line x1={left} x2={width - right} y1={y} y2={y} stroke="#253033" strokeWidth="1" /><text x={left - 8} y={y + 3} fill="#748285" fontSize="10" textAnchor="end" fontFamily="IBM Plex Mono">{tickValue}</text></g>
    })}
    <polygon points={areaPoints} fill={color} fillOpacity="0.09" />
    <polyline points={points} fill="none" stroke={color} strokeWidth="2" vectorEffect="non-scaling-stroke" strokeLinejoin="round" strokeLinecap="round" />
    {coordinates.map((point, index) => <circle key={`${point.time}-${index}`} cx={point.x} cy={point.y} r="3" fill={color}><title>{`${point.time}: ${point.value}`}</title></circle>)}
    {timeTicks.map((item, index) => {
      const originalIndex = data.indexOf(item)
      const x = left + (data.length < 2 ? plotWidth / 2 : (originalIndex / (data.length - 1)) * plotWidth)
      return <text key={`${item.time}-${index}`} x={x} y={height - 8} fill="#748285" fontSize="10" textAnchor="middle" fontFamily="IBM Plex Mono">{item.time}</text>
    })}
  </svg>
}

function SignalChart({ title, unit, data, dataKey, color }) {
  return <div className="signal-chart"><div className="signal-chart-heading"><span>{title}</span><small>{unit}</small></div><div className="signal-chart-plot"><TrendChart data={data} dataKey={dataKey} color={color} label={`${title} over time`} /></div></div>
}

function PullRequestsPage({ activeProject, refresh, loading, error }) {
  const [rows, setRows] = useState([])
  const [listLoading, setListLoading] = useState(false)
  const [listError, setListError] = useState('')
  const [page, setPage] = useState(0)
  const [statusFilter, setStatusFilter] = useState('')
  const [pageInfo, setPageInfo] = useState({ totalElements: 0, totalPages: 0, hasNext: false, hasPrevious: false })

  async function loadPullRequests(requestedPage = page, requestedStatus = statusFilter) {
    if (!activeProject) return
    setListLoading(true)
    setListError('')
    try {
      const data = await api.pullRequests(activeProject.id, requestedPage, 25, requestedStatus)
      setRows(data?.content || [])
      setPageInfo(data || { totalElements: 0, totalPages: 0, hasNext: false, hasPrevious: false })
      setPage(data?.page || 0)
    } catch (e) {
      setListError(e.message)
    } finally {
      setListLoading(false)
    }
  }

  useEffect(() => { setPage(0); loadPullRequests(0, statusFilter) }, [activeProject?.id, statusFilter])

  if (!activeProject) {
    return <EmptyState title="No project selected" detail="Choose a project to begin reviewing the pull requests associated with it." />
  }

  return <>
    <PageHeading eyebrow="CODE REVIEW" title="Pull requests" subtitle="Recent PR signals, changed files, and the latest risk context for the selected workspace." action={<button className="button button-secondary" onClick={() => loadPullRequests()}><RefreshCw size={14} /> Refresh</button>} />
    {error && <ErrorState message={error} onRetry={refresh} />}
    {listError && <ErrorState message={listError} onRetry={() => loadPullRequests()} />}
    <div className="catalog-toolbar pr-toolbar"><label className="filter-field">Status<select value={statusFilter} onChange={event => setStatusFilter(event.target.value)}><option value="">All statuses</option><option value="OPEN">Open</option><option value="MERGED">Merged</option><option value="CLOSED">Closed</option></select></label><span className="muted-cell">{formatNumber(pageInfo.totalElements)} pull requests</span></div>
    {listLoading ? <section className="panel"><LoadingRows count={4} /></section> : rows.length ? <section className="panel"><div className="table-scroll"><table><thead><tr><th>PR</th><th>Repository</th><th>Status</th><th>Files</th><th>Affected endpoints</th><th>Latest risk</th></tr></thead><tbody>{rows.map(pr => <tr key={pr.id}><td><Link to={`/pull-requests/${pr.id}`} className="endpoint-name"><span className="repo-icon"><GitPullRequest size={14} /></span><div><strong>#{pr.githubPrNumber}</strong><small>{pr.title}</small></div></Link></td><td className="muted-cell">{pr.repositoryName}</td><td><span className={`risk-badge risk-${(pr.status || 'open').toLowerCase()}`}>{pr.status || 'OPEN'}</span></td><td>{pr.changedFileCount}</td><td>{pr.affectedEndpointCount}</td><td>{pr.latestRiskTier ? <RiskBadge tier={pr.latestRiskTier} /> : <span className="muted-cell">Unassessed</span>}</td></tr>)}</tbody></table></div><div className="pagination-footer"><span>Page {pageInfo.totalPages ? page + 1 : 0} of {pageInfo.totalPages}</span><div className="form-actions"><button className="button button-secondary button-small" disabled={listLoading || !pageInfo.hasPrevious} onClick={() => loadPullRequests(page - 1)}>Previous</button><button className="button button-secondary button-small" disabled={listLoading || !pageInfo.hasNext} onClick={() => loadPullRequests(page + 1)}>Next</button></div></div></section> : <EmptyState title={statusFilter ? 'No matching pull requests' : 'No pull requests yet'} detail="Pull requests appear after GitHub webhook delivery. Status filters apply across the selected project." />}
  </>
}

function formatCategory(category) {
  if (!category) return ''
  return category
    .toLowerCase()
    .split('_')
    .map(w => w.charAt(0).toUpperCase() + w.slice(1))
    .join(' ')
}

function StructuredReviewSection({ review, reviewStatus, latestRisk }) {
  if (!review) {
    return (
      <section className="panel review-panel">
        <div className="panel-heading">
          <div><span className="eyebrow">ADVISORY REVIEW</span><h2>LLM review</h2></div>
          <span className="review-state">{reviewStatus || 'NOT_REQUESTED'}</span>
        </div>
        <p className="muted-cell">
          {['PENDING', 'PROCESSING'].includes(reviewStatus)
            ? 'Review is running; this view will refresh automatically.'
            : reviewStatus === 'FAILED'
            ? 'Review could not be completed. Deterministic risk remains available.'
            : 'No review has been generated for this pull request.'}
        </p>
      </section>
    )
  }

  const analysis = review.analysis
  const aiRiskLevel = analysis?.overallRisk?.level || review.decision || 'LOW'
  const aiScore = analysis?.overallRisk?.score != null ? analysis.overallRisk.score : review.score
  const confidence = analysis?.reviewConfidence != null
    ? analysis.reviewConfidence
    : analysis?.overallRisk?.confidence
  const riskAlignment = analysis?.riskAlignment
  const riskAlignmentMismatch = riskAlignment && (
    riskAlignment.alignment === 'DIFFERENT'
    || riskAlignment.alignment === 'different'
    || riskAlignment.explanation
    || riskAlignment.deterministicRisk
    || riskAlignment.llmRisk
  )

  return (
    <section className="panel review-panel">
      <div className="panel-heading">
        <div>
          <span className="eyebrow">ADVISORY REVIEW · {(review.provider || 'AI').toUpperCase()}</span>
          <h2>AI Code Review Analysis</h2>
        </div>
        <span className="review-state">{reviewStatus || 'COMPLETED'}</span>
      </div>

      {/* Difference between deterministic risk and AI advisory assessment */}
      <div className="risk-comparison-card">
        <div className="risk-comparison-header">
          <span className="eyebrow">RISK MODEL SEPARATION</span>
          <h3>Deterministic System Risk vs AI Advisory Assessment</h3>
          <p>
            Deterministic risk is EndpointGuard&apos;s authoritative production gate calculated from mapped endpoint criticality and live traffic data. The AI code review provides contextual advisory intelligence and does not replace or silently alter system risk.
          </p>
        </div>
        <div className="risk-comparison-split">
          <div className="risk-comparison-side deterministic-side">
            <div className="comparison-side-title">
              <Shield size={15} />
              <strong>Deterministic System Risk</strong>
              <span className="authoritative-tag">AUTHORITATIVE GATE</span>
            </div>
            <div className="comparison-values">
              <RiskBadge tier={latestRisk?.tier || 'INSUFFICIENT_DATA'} />
              <span className="comparison-score">
                {latestRisk?.score != null ? `${Number(latestRisk.score).toFixed(2)} score` : 'Unassessed'}
              </span>
            </div>
            <p className="comparison-note">
              Status: {latestRisk?.evaluationStatus || 'Pending'} · {latestRisk?.dataQualityNotes?.join(', ') || 'Based on active traffic and baseline weights'}
            </p>
          </div>
          <div className="risk-comparison-side ai-side">
            <div className="comparison-side-title">
              <Sparkles size={15} />
              <strong>AI Advisory Assessment</strong>
              <span className="advisory-tag">ADVISORY CONTEXT</span>
            </div>
            <div className="comparison-values">
              <RiskBadge tier={aiRiskLevel} />
              <span className="comparison-score">
                {aiScore != null ? `${Number(aiScore).toFixed(1)} / 100` : '—'}
              </span>
            </div>
            <p className="comparison-note">
              Confidence: {confidence != null ? `${(confidence * 100).toFixed(0)}%` : '—'} · Provider: {review.provider}
              {review.fallback ? ' (deterministic fallback)' : ''}
            </p>
          </div>
        </div>
      </div>

      {/* Structured metrics summary */}
      <div className="review-metric-strip">
        <MetricTile
          label="AI advisory risk"
          value={aiRiskLevel}
          note={aiScore != null ? `Advisory score ${Number(aiScore).toFixed(1)}/100` : 'Advisory level'}
          icon={ShieldAlert}
          accent={aiRiskLevel === 'CRITICAL' ? 'amber' : 'green'}
          compact
        />
        <MetricTile
          label="Review confidence"
          value={confidence != null ? `${(confidence * 100).toFixed(0)}%` : '—'}
          note="LLM assessment certainty"
          icon={Gauge}
          compact
        />
        <MetricTile
          label="Files analyzed"
          value={analysis?.files?.length || 0}
          note="Grounded per-file analysis"
          icon={Code2}
          compact
        />
        <MetricTile
          label="Critical findings"
          value={analysis?.criticalFindings?.length || 0}
          note={analysis?.criticalFindings?.length ? 'Issues requiring attention' : 'No critical findings'}
          icon={AlertTriangle}
          accent={analysis?.criticalFindings?.length ? 'amber' : 'green'}
          compact
        />
      </div>

      {/* Overall Assessment & What Changed */}
      <div className="review-sub-panel">
        <span className="eyebrow">ASSESSMENT</span>
        <h3>Overall Assessment</h3>
        <p className="review-summary">{analysis?.overallAssessment || review.summary}</p>
        {analysis?.overallRisk?.reason && (
          <p className="muted-cell" style={{ marginTop: '6px' }}>
            <strong>Risk rationale:</strong> {analysis.overallRisk.reason}
          </p>
        )}
        {analysis?.changeSummary && (
          <div style={{ marginTop: '12px' }}>
            <span className="eyebrow">SCOPE OF CHANGE</span>
            <p className="review-summary" style={{ marginTop: '4px' }}>{analysis.changeSummary}</p>
          </div>
        )}
        {riskAlignmentMismatch && (
          <div className="recommendation-banner" style={{ marginTop: '12px', borderColor: 'rgba(236, 151, 53, 0.45)' }}>
            <ShieldAlert size={16} />
            <div>
              <strong>Risk alignment:</strong> {riskAlignment?.alignment === 'DIFFERENT' || riskAlignment?.alignment === 'different'
                ? 'AI advisory risk differs from deterministic system risk.'
                : 'AI advisory review included a risk distinction.'}
              <div className="muted-cell" style={{ marginTop: '4px' }}>
                Deterministic: {riskAlignment?.deterministicRisk || latestRisk?.tier || '—'} · AI: {riskAlignment?.llmRisk || aiRiskLevel || '—'}
              </div>
              {riskAlignment?.explanation && (
                <div className="muted-cell" style={{ marginTop: '4px' }}>{riskAlignment.explanation}</div>
              )}
            </div>
          </div>
        )}
        {analysis?.recommendation && (
          <div className="recommendation-banner">
            <Info size={16} />
            <div>
              <strong>Actionable Recommendation:</strong> {analysis.recommendation}
            </div>
          </div>
        )}
      </div>

      {/* Business Impact */}
      {analysis?.businessImpact && (
        <div className="review-sub-panel">
          <span className="eyebrow">BUSINESS RELEVANCE</span>
          <h3>Business &amp; Capability Impact</h3>
          <dl className="business-impact-card">
            <dt>Affected Capability</dt>
            <dd><strong>{analysis.businessImpact.affectedCapability}</strong></dd>
            <dt>Impact Summary</dt>
            <dd>{analysis.businessImpact.summary}</dd>
            <dt>Confidence</dt>
            <dd>
              {analysis.businessImpact.confidence != null
                ? `${(analysis.businessImpact.confidence * 100).toFixed(0)}% confidence`
                : '—'}
            </dd>
          </dl>
        </div>
      )}

      {/* Critical Findings */}
      {analysis?.criticalFindings && analysis.criticalFindings.length > 0 && (
        <div className="review-sub-panel">
          <span className="eyebrow">ATTENTION REQUIRED</span>
          <h3>Critical &amp; High Findings ({analysis.criticalFindings.length})</h3>
          <div className="critical-findings-list">
            {analysis.criticalFindings.map((finding, idx) => {
              const sev = (finding.severity || 'HIGH').toLowerCase()
              return (
                <article key={idx} className={`critical-finding-card finding-card-${sev}`}>
                  <div className="finding-header">
                    <RiskBadge tier={finding.severity} />
                    <span className="category-tag">{formatCategory(finding.category)}</span>
                    <code>{finding.file}</code>
                  </div>
                  <h4 className="finding-title">{finding.finding}</h4>
                  <p className="finding-why"><strong>Why it matters:</strong> {finding.whyItMatters}</p>
                  {finding.evidence && (
                    <div className="finding-evidence">
                      <strong>Evidence:</strong> {finding.evidence}
                    </div>
                  )}
                  {finding.recommendedAction && (
                    <p className="finding-action">
                      <ArrowRight size={13} />
                      <span><strong>Recommended action:</strong> {finding.recommendedAction}</span>
                    </p>
                  )}
                </article>
              )
            })}
          </div>
        </div>
      )}

      {/* File-by-File Analysis */}
      {analysis?.files && analysis.files.length > 0 && (
        <div className="review-sub-panel">
          <span className="eyebrow">FILE-BY-FILE AUDIT</span>
          <h3>Per-File Analysis ({analysis.files.length})</h3>
          <div className="file-analysis-list">
            {analysis.files.map((f, idx) => (
              <details key={idx} className="file-analysis-card" open>
                <summary>
                  <div className="file-card-top">
                    <code>{f.file}</code>
                    <div className="file-card-badges">
                      <RiskBadge tier={f.riskLevel} />
                      {f.confidence != null && (
                        <span className="file-confidence">{(f.confidence * 100).toFixed(0)}% conf</span>
                      )}
                    </div>
                  </div>
                </summary>

                <div style={{ marginTop: '10px' }}>
                  {f.riskCategories && f.riskCategories.length > 0 && (
                    <div className="category-tags">
                      {f.riskCategories.map(cat => (
                        <span key={cat} className="category-tag">{formatCategory(cat)}</span>
                      ))}
                    </div>
                  )}

                  <div className="file-details-grid">
                    <div className="file-detail-block">
                      <strong>WHAT CHANGED</strong>
                      <p>{f.whatChanged || f.changeSummary}</p>
                    </div>
                    <div className="file-detail-block">
                      <strong>WHAT IT DOES</strong>
                      <p>{f.whatItDoes}</p>
                    </div>
                    <div className="file-detail-block">
                      <strong>TECHNICAL IMPACT</strong>
                      <p>{f.technicalImpact}</p>
                    </div>
                    <div className="file-detail-block">
                      <strong>BUSINESS IMPACT</strong>
                      <p>{f.businessImpact}</p>
                    </div>
                  </div>

                  {f.evidence && f.evidence.length > 0 && (
                    <div style={{ marginTop: '10px' }}>
                      <span className="eyebrow">EVIDENCE</span>
                      <ul className="file-evidence-list">
                        {f.evidence.map((ev, i) => (
                          <li key={i}>{ev}</li>
                        ))}
                      </ul>
                    </div>
                  )}

                  {f.affectedEndpoints && f.affectedEndpoints.length > 0 && (
                    <div style={{ marginTop: '10px' }}>
                      <span className="eyebrow">AFFECTED ENDPOINTS FOR THIS FILE</span>
                      <div className="table-scroll" style={{ marginTop: '6px' }}>
                        <table>
                          <thead>
                            <tr>
                              <th>Method</th>
                              <th>Path</th>
                              <th>Criticality</th>
                              <th>Impact</th>
                            </tr>
                          </thead>
                          <tbody>
                            {f.affectedEndpoints.map((ep, i) => (
                              <tr key={i}>
                                <td>
                                  <span className={`method method-${(ep.method || 'get').toLowerCase()}`}>
                                    {ep.method || 'GET'}
                                  </span>
                                </td>
                                <td className="muted-cell"><code>{ep.path}</code></td>
                                <td><CriticalityPill value={ep.criticality} /></td>
                                <td className="muted-cell">{ep.impact}</td>
                              </tr>
                            ))}
                          </tbody>
                        </table>
                      </div>
                    </div>
                  )}
                </div>
              </details>
            ))}
          </div>
        </div>
      )}

      {/* Affected Endpoints Impact (PR-level) */}
      {analysis?.endpointImpact && analysis.endpointImpact.length > 0 && (
        <div className="review-sub-panel">
          <span className="eyebrow">API SURFACE</span>
          <h3>Affected Endpoints Impact ({analysis.endpointImpact.length})</h3>
          <div className="table-scroll">
            <table>
              <thead>
                <tr>
                  <th>Method</th>
                  <th>Path</th>
                  <th>Criticality</th>
                  <th>Impact</th>
                  <th>Reason</th>
                </tr>
              </thead>
              <tbody>
                {analysis.endpointImpact.map((ep, idx) => (
                  <tr key={idx}>
                    <td>
                      <span className={`method method-${(ep.method || 'get').toLowerCase()}`}>
                        {ep.method || 'GET'}
                      </span>
                    </td>
                    <td className="muted-cell"><code>{ep.path}</code></td>
                    <td><CriticalityPill value={ep.criticality} /></td>
                    <td>{ep.impact}</td>
                    <td className="muted-cell">{ep.reason}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      )}

      {/* Legacy / Fallback findings list */}
      {(!analysis && review.findings && review.findings.length > 0) && (
        <div className="review-sub-panel">
          <span className="eyebrow">FINDINGS</span>
          <h3>Review Findings</h3>
          <ul className="review-findings">
            {review.findings.map((finding, index) => (
              <li key={`${index}-${finding}`}>{finding}</li>
            ))}
          </ul>
        </div>
      )}

      <div style={{ marginTop: '16px', paddingTop: '12px', borderTop: '1px solid var(--border)' }}>
        <p className="muted-cell">
          Provider: {review.provider}
          {review.fallback ? ' · deterministic fallback' : ''}.
          This signal is advisory code review and separate from the deterministic system risk score.
        </p>
      </div>
    </section>
  )
}

function PullRequestDetailPage({ activeProject }) {
  const { pullRequestId } = useParams()
  const [detail, setDetail] = useState(null)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')
  const [refreshKey, setRefreshKey] = useState(0)

  useEffect(() => {
    if (!activeProject || !pullRequestId) return
    let cancelled = false
    let timer
    let attempts = 0
    setLoading(true)
    setError('')
    const load = async () => {
      try {
        const data = await api.pullRequest(activeProject.id, pullRequestId)
        if (cancelled) return
        setDetail(data)
        if (['PENDING', 'PROCESSING'].includes(data.reviewStatus) && attempts++ < 10) {
          timer = window.setTimeout(load, 1500)
        }
      } catch (e) {
        if (!cancelled) setError(e.message)
      } finally {
        if (!cancelled) setLoading(false)
      }
    }
    load()
    return () => { cancelled = true; window.clearTimeout(timer) }
  }, [activeProject?.id, pullRequestId, refreshKey])

  if (!activeProject) return <EmptyState title="No project selected" detail="Choose a project to inspect pull request details." />
  if (loading) return <section className="panel"><LoadingRows count={4} /></section>
  if (error) return <ErrorState message={error} onRetry={() => setRefreshKey(value => value + 1)} />
  if (!detail) return <EmptyState title="Pull request not found" detail="This pull request may not belong to the current workspace or may have been removed." action={<Link className="text-link" to="/pull-requests">Return to pull requests</Link>} />

  return <>
    <button className="back-link" onClick={() => window.history.back()}><ArrowLeft size={14} /> Back to pull requests</button>
    <PageHeading eyebrow={`${detail.repositoryName} · #${detail.githubPrNumber}`} title={detail.title} subtitle={`${detail.author} · ${detail.status}`} action={detail.riskHistory?.[0] ? <RiskBadge tier={detail.riskHistory[0].tier} /> : <span className="muted-cell">No risk score yet</span>} />
    <section className="panel"><div className="panel-heading"><div><span className="eyebrow">DETAILS</span><h2>Review context</h2></div></div><div className="endpoint-kpis"><MetricTile label="Changed files" value={detail.changedFiles?.length || 0} note="Stored by the webhook" icon={Code2} /><MetricTile label="Affected endpoints" value={detail.affectedEndpoints?.length || 0} note="Matched from source patterns" icon={Layers3} /><MetricTile label="Latest tier" value={detail.riskHistory?.[0]?.tier || '—'} note={detail.riskHistory?.[0] ? `Scored ${Number(detail.riskHistory[0].score).toFixed(2)}` : 'No evaluation yet'} icon={Shield} /></div></section>
    <div className="dashboard-grid">
      <section className="panel"><div className="panel-heading"><div><span className="eyebrow">FILES</span><h2>Changed files</h2></div></div>{detail.changedFiles?.length ? <div className="changed-file-list">{detail.changedFiles.map(file => <article className="changed-file-row" key={file.id}><div className="changed-file-heading"><code>{file.filePath}</code><span className="diff-stats"><span>+{file.additions}</span><span>−{file.deletions}</span></span></div><details className="diff-disclosure"><summary>View patch</summary><pre>{file.patch || 'Patch unavailable for this file.'}</pre></details></article>)}</div> : <EmptyState title="No files recorded" detail="This PR has no changed-file payload attached." />}</section>
      <section className="panel"><div className="panel-heading"><div><span className="eyebrow">MAPPED</span><h2>Affected endpoints</h2></div></div>{detail.affectedEndpoints?.length ? <div className="table-scroll"><table><thead><tr><th>Method</th><th>Path</th><th>Criticality</th></tr></thead><tbody>{detail.affectedEndpoints.map(endpoint => <tr key={endpoint.endpointId}><td><span className={`method method-${(endpoint.method || 'get').toLowerCase()}`}>{endpoint.method || 'GET'}</span></td><td className="muted-cell"><code>{endpoint.pathPattern}</code></td><td><CriticalityPill value={endpoint.criticality} /></td></tr>)}</tbody></table></div> : <EmptyState title="No affected endpoints" detail="No endpoint mappings are linked to this PR yet." />}</section>
    </div>
    <section className="panel"><div className="panel-heading"><div><span className="eyebrow">RISK</span><h2>Risk history</h2></div></div>{detail.riskHistory?.length ? <div className="table-scroll"><table><thead><tr><th>Evaluated</th><th>Tier</th><th>Score</th><th>Data status</th><th>Notes</th></tr></thead><tbody>{detail.riskHistory.map(entry => <tr key={entry.id}><td className="muted-cell">{formatDate(entry.evaluatedAt)}</td><td><RiskBadge tier={entry.tier} /></td><td>{Number(entry.score).toFixed(3)}</td><td>{entry.evaluationStatus}</td><td className="muted-cell">{entry.dataQualityNotes?.join(', ') || 'Complete'}</td></tr>)}</tbody></table></div> : <EmptyState title="No risk history" detail="Automatic scoring runs when supported open pull-request webhook actions arrive." />}</section>
    <StructuredReviewSection review={detail.review} reviewStatus={detail.reviewStatus} latestRisk={detail.riskHistory?.[0]} />
  </>
}

function MonitoringPage({ activeProject }) {
  const [records, setRecords] = useState([])
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')

  async function load() {
    if (!activeProject) return
    setLoading(true); setError('')
    try { setRecords(await api.monitoring(activeProject.id) || []) }
    catch (e) { setError(e.message) }
    finally { setLoading(false) }
  }

  useEffect(() => { load() }, [activeProject?.id])
  if (!activeProject) return <EmptyState title="No project selected" detail="Choose a project to inspect post-merge monitoring." />
  return <>
    <PageHeading eyebrow="POST-MERGE SIGNALS" title="Monitoring" subtitle="Traffic is compared with the pre-merge baseline after each observation window closes." action={<button className="button button-secondary" onClick={load}><RefreshCw size={14} /> Refresh</button>} />
    {error && <ErrorState message={error} onRetry={load} />}
    {loading ? <section className="panel"><LoadingRows count={4} /></section> : records.length ? <section className="panel"><div className="table-scroll"><table><thead><tr><th>Pull request</th><th>Endpoint</th><th>Window</th><th>Requests</th><th>5xx rate</th><th>Latency</th><th>Verdict</th></tr></thead><tbody>{records.map(record => <tr key={record.id}><td><Link className="text-link" to={`/pull-requests/${record.pullRequestId}`}>{record.repositoryName} #{record.githubPrNumber}</Link></td><td><span className={`method method-${(record.endpointMethod || 'get').toLowerCase()}`}>{record.endpointMethod || 'GET'}</span> <code>{record.endpointPath}</code></td><td className="monitoring-window">{formatDate(record.windowStart)}<small>to {formatDate(record.windowEnd)}</small></td><td>{formatNumber(record.baselineRequestCount)} / {formatNumber(record.observedRequestCount)}</td><td>{record.baselineErrorRate == null ? '—' : `${(record.baselineErrorRate * 100).toFixed(2)}%`} / {record.observedErrorRate == null ? '—' : `${(record.observedErrorRate * 100).toFixed(2)}%`}</td><td>{record.baselineLatencyMs == null ? '—' : `${record.baselineLatencyMs.toFixed(0)} ms`} / {record.observedLatencyMs == null ? '—' : `${record.observedLatencyMs.toFixed(0)} ms`}</td><td><span className={`monitoring-verdict verdict-${record.verdict.toLowerCase()}`}>{record.verdict}</span></td></tr>)}</tbody></table></div></section> : <EmptyState title="No merge windows yet" detail="A monitoring window begins when an affected pull request is merged. Verdicts appear after the configured observation period and sufficient traffic." />}
  </>
}

function AuditLogsPage() {
  const [records, setRecords] = useState([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')

  async function load() {
    setLoading(true); setError('')
    try { setRecords(await api.auditLogs() || []) }
    catch (e) { setError(e.message) }
    finally { setLoading(false) }
  }

  useEffect(() => { load() }, [])
  return <>
    <PageHeading eyebrow="ACCOUNT ACTIVITY" title="Audit logs" subtitle="Recent events recorded for your account." action={<button className="button button-secondary" onClick={load}><RefreshCw size={14} /> Refresh</button>} />
    {error && <ErrorState message={error} onRetry={load} />}
    {loading ? <section className="panel"><LoadingRows count={4} /></section> : records.length ? <section className="panel"><div className="table-scroll"><table><thead><tr><th>When</th><th>Action</th><th>Entity</th><th>Details</th></tr></thead><tbody>{records.map(record => <tr key={record.id}><td className="muted-cell">{formatDate(record.createdAt)}</td><td>{record.action.replaceAll('_', ' ')}</td><td>{record.entityType} {record.entityId}</td><td className="audit-details">{Object.entries(record.details || {}).map(([key, value]) => `${key.replaceAll('_', ' ')}: ${value}`).join(' · ') || '—'}</td></tr>)}</tbody></table></div></section> : <EmptyState title="No activity recorded" detail="Risk assessments, password changes, and monitoring verdicts will appear here." />}
  </>
}

function RiskRulesNotice() {
  return <>
    <PageHeading eyebrow="BACKEND CAPABILITY" title="Risk rules" subtitle="The current API supports configured risk scoring but not rule management." />
    <section className="panel capability-panel"><div className="capability-large-icon"><Shield size={22} /></div><div><span className="eyebrow">READ-ONLY CONFIGURATION</span><h2>Risk rule management is not exposed</h2><p>Scoring uses the active weights configured by the backend. Changes remain an operator configuration task.</p></div></section>
  </>
}

function ProfilePage() {
  const [profile, setProfile] = useState(null)
  const [form, setForm] = useState({ currentPassword: '', newPassword: '', confirmPassword: '' })
  const [loading, setLoading] = useState(true)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [notice, setNotice] = useState('')

  useEffect(() => {
    api.profile().then(setProfile).catch(e => setError(e.message)).finally(() => setLoading(false))
  }, [])

  async function changePassword(event) {
    event.preventDefault(); setError(''); setNotice('')
    if (form.newPassword !== form.confirmPassword) { setError('New password and confirmation do not match.'); return }
    if (form.newPassword.length < 8) { setError('New password must contain at least 8 characters.'); return }
    setBusy(true)
    try {
      const response = await api.changePassword(form.currentPassword, form.newPassword)
      setToken(response.token)
      setForm({ currentPassword: '', newPassword: '', confirmPassword: '' })
      setNotice('Password updated. Your session has been refreshed.')
    } catch (e) { setError(e.message) }
    finally { setBusy(false) }
  }

  return <>
    <PageHeading eyebrow="ACCOUNT" title="Profile & security" subtitle="Review your account and update its password." />
    {error && <div className="inline-error" role="alert">{error}</div>}{notice && <div className="inline-success"><Check size={15} />{notice}</div>}
    {loading ? <section className="panel"><LoadingRows count={2} /></section> : profile && <div className="profile-grid"><section className="panel profile-panel"><span className="eyebrow">PROFILE</span><div className="profile-field"><small>Email</small><strong>{profile.email}</strong></div><div className="profile-field"><small>Role</small><strong>{profile.role}</strong></div><div className="profile-field"><small>Member since</small><strong>{formatDate(profile.createdAt)}</strong></div></section><section className="panel profile-panel"><div className="panel-heading"><div><span className="eyebrow">CREDENTIALS</span><h2>Change password</h2></div><KeyRound size={17} /></div><form className="stack-form password-form" onSubmit={changePassword}><label htmlFor="current-password">Current password</label><input id="current-password" type="password" autoComplete="current-password" required value={form.currentPassword} onChange={e => setForm({ ...form, currentPassword: e.target.value })} /><label htmlFor="new-password">New password</label><input id="new-password" type="password" autoComplete="new-password" minLength={8} maxLength={72} required value={form.newPassword} onChange={e => setForm({ ...form, newPassword: e.target.value })} /><label htmlFor="confirm-password">Confirm new password</label><input id="confirm-password" type="password" autoComplete="new-password" minLength={8} maxLength={72} required value={form.confirmPassword} onChange={e => setForm({ ...form, confirmPassword: e.target.value })} /><button className="button button-primary" disabled={busy}>{busy ? 'Updating…' : 'Update password'}</button></form></section></div>}
  </>
}

function localDateNow() { return new Date().toISOString().slice(0, 19) }
function localDateHoursAgo(hours) { return new Date(Date.now() - hours * 60 * 60 * 1000).toISOString().slice(0, 19) }
function formatNumber(value) { return new Intl.NumberFormat('en-US', { maximumFractionDigits: 0 }).format(value || 0) }
function parseBackendDate(value) {
  if (!value) return null
  const iso = /(?:Z|[+-]\d{2}:\d{2})$/i.test(value) ? value : `${value}Z`
  const date = new Date(iso)
  return Number.isNaN(date.getTime()) ? null : date
}
function formatDate(value) {
  const date = parseBackendDate(value)
  return date ? new Intl.DateTimeFormat('en-IN', { dateStyle: 'medium', timeStyle: 'short', timeZone: 'Asia/Kolkata' }).format(date) : value || 'recently'
}
function formatTimeInIst(value) {
  const date = parseBackendDate(value)
  return date ? new Intl.DateTimeFormat('en-IN', { hour: '2-digit', minute: '2-digit', hour12: false, timeZone: 'Asia/Kolkata' }).format(date) : ''
}

export default function App() {
  const [authenticated, setAuthenticated] = useState(Boolean(getToken()))
  const [user, setUser] = useState('')
  const [projects, setProjects] = useState([])
  const [repositories, setRepositories] = useState([])
  const [endpoints, setEndpoints] = useState([])
  const [activeProjectId, setActiveProjectId] = useState('')
  const [loading, setLoading] = useState(false)
  const [loadError, setLoadError] = useState('')
  const [apiStatus, setApiStatus] = useState('checking')
  const navigate = useNavigate()
  const activeProject = projects.find(project => String(project.id) === String(activeProjectId)) || projects[0] || null

  useEffect(() => {
    const handleUnauthorized = () => { setAuthenticated(false); setUser(''); navigate('/login', { replace: true }) }
    window.addEventListener('endpointguard:unauthorized', handleUnauthorized)
    return () => window.removeEventListener('endpointguard:unauthorized', handleUnauthorized)
  }, [navigate])

  useEffect(() => {
    if (authenticated) loadWorkspace()
  }, [authenticated])

  async function loadWorkspace(preferredProjectId) {
    setLoading(true); setLoadError('')
    setApiStatus('checking')
    try {
      const projectList = await api.projects()
      const selected = projectList.find(project => String(project.id) === String(preferredProjectId || activeProjectId)) || projectList[0] || null
      setProjects(projectList)
      setActiveProjectId(selected?.id || '')
      if (selected) {
        const repos = await api.repositories(selected.id)
        setRepositories(repos)
        const endpointSets = await Promise.all(repos.map(repo => api.endpoints(repo.id)))
        setEndpoints(endpointSets.flat())
      } else { setRepositories([]); setEndpoints([]) }
      setApiStatus('online')
    } catch (e) { setLoadError(e.message); setApiStatus('offline') }
    finally { setLoading(false) }
  }

  async function reloadProject(projectId = activeProject?.id) {
    await loadWorkspace(projectId)
  }

  function logout() {
    clearToken(); setAuthenticated(false); setUser(''); setProjects([]); setRepositories([]); setEndpoints([]); navigate('/login', { replace: true })
  }

  function loginComplete() {
    const payload = getToken()?.split('.')[1]
    if (payload) {
      try { setUser(JSON.parse(atob(payload.replace(/-/g, '+').replace(/_/g, '/'))).sub || '') } catch { setUser('') }
    }
    setAuthenticated(true); navigate('/', { replace: true })
  }

  if (!authenticated) return <Routes><Route path="/login" element={<Login onLogin={loginComplete} />} /><Route path="*" element={<Navigate to="/login" replace />} /></Routes>

  return <AppShell user={user} onLogout={logout} projects={projects} activeProject={activeProject} setActiveProject={project => project && reloadProject(project.id)} endpointCount={endpoints.length} apiStatus={apiStatus}>
    <Routes>
      <Route path="/" element={<Dashboard projects={projects} repositories={repositories} endpoints={endpoints} refresh={() => reloadProject()} loading={loading} error={loadError} />} />
      <Route path="/projects" element={<ProjectsPage projects={projects} repositories={repositories} refresh={reloadProject} loading={loading} error={loadError} activeProject={activeProject} setActiveProject={project => project && reloadProject(project.id)} />} />
      <Route path="/pull-requests" element={<PullRequestsPage activeProject={activeProject} refresh={() => reloadProject()} loading={loading} error={loadError} />} />
      <Route path="/pull-requests/:pullRequestId" element={<PullRequestDetailPage activeProject={activeProject} />} />
      <Route path="/endpoints" element={<EndpointsPage endpoints={endpoints} projects={projects} repositories={repositories} refresh={() => reloadProject()} loading={loading} error={loadError} />} />
      <Route path="/endpoints/:endpointId" element={<EndpointDetail endpoints={endpoints} projects={projects} repositories={repositories} />} />
      <Route path="/monitoring" element={<MonitoringPage activeProject={activeProject} />} />
      <Route path="/risk-rules" element={<RiskRulesNotice />} />
      <Route path="/audit-logs" element={<AuditLogsPage />} />
      <Route path="/profile" element={<ProfilePage />} />
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  </AppShell>
}
