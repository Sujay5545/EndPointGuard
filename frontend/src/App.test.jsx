import '@testing-library/jest-dom/vitest'
import { describe, expect, it, vi } from 'vitest'
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import App from './App.jsx'
import { api, setToken } from './api.js'

describe('sign-in flow', () => {
  it('authenticates and loads the workspace', async () => {
    const payload = btoa(JSON.stringify({ sub: 'phase8@example.com' }))
    vi.spyOn(api, 'login').mockResolvedValue({ token: `header.${payload}.signature` })
    vi.spyOn(api, 'projects').mockResolvedValue([])

    render(<MemoryRouter initialEntries={['/login']}><App /></MemoryRouter>)
    fireEvent.change(screen.getByLabelText('Work email'), { target: { value: 'phase8@example.com' } })
    fireEvent.change(screen.getByLabelText('Password'), { target: { value: 'password123' } })
    fireEvent.click(screen.getByRole('button', { name: 'Sign in' }))

    await waitFor(() => expect(api.login).toHaveBeenCalledWith('phase8@example.com', 'password123'))
    await waitFor(() => expect(api.projects).toHaveBeenCalledOnce())
    expect(screen.queryByLabelText('Work email')).not.toBeInTheDocument()
    cleanup()
    sessionStorage.clear()
    vi.restoreAllMocks()
  })

  it('shows a user-friendly message for incorrect passwords', async () => {
    vi.spyOn(api, 'login').mockRejectedValue(new Error('Your session has expired. Sign in again to continue.'))

    render(<MemoryRouter initialEntries={['/login']}><App /></MemoryRouter>)
    fireEvent.change(screen.getByLabelText('Work email'), { target: { value: 'phase8@example.com' } })
    fireEvent.change(screen.getByLabelText('Password'), { target: { value: 'wrong-password' } })
    fireEvent.click(screen.getByRole('button', { name: 'Sign in' }))

    await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent('Incorrect password.'))
    cleanup()
    sessionStorage.clear()
    vi.restoreAllMocks()
  })
})

describe('overview traffic timeline', () => {
  it('plots actual minute request counts and reloads for each selected range', async () => {
    const payload = btoa(JSON.stringify({ sub: 'reviewer@example.com' }))
    setToken(`header.${payload}.signature`)
    const latest = new Date()
    latest.setUTCSeconds(0, 0)
    const earlier = new Date(latest.getTime() - 2 * 60 * 1000)

    vi.spyOn(api, 'projects').mockResolvedValue([{ id: 1, name: 'EndpointGuard Core' }])
    vi.spyOn(api, 'repositories').mockResolvedValue([{ id: 10, projectId: 1, githubRepoFullName: 'acme/core-api' }])
    vi.spyOn(api, 'endpoints').mockResolvedValue([{ id: 7, projectId: 1, repositoryId: 10, method: 'GET', pathPattern: '/api/payments', criticality: 'HIGH' }])
    vi.spyOn(api, 'endpointMetrics').mockResolvedValue([
      { bucketStart: earlier.toISOString().slice(0, 19), requestCount: 21 },
      { bucketStart: latest.toISOString().slice(0, 19), requestCount: 50 },
    ])

    render(<MemoryRouter><App /></MemoryRouter>)
    const rangeControls = await screen.findByRole('group', { name: 'Overview traffic time range' })
    await waitFor(() => expect(document.querySelectorAll('.traffic-panel svg circle')).toHaveLength(1441))

    const pointTitles = [...document.querySelectorAll('.traffic-panel svg circle title')].map(title => title.textContent)
    expect(pointTitles.some(title => title.endsWith(': 21'))).toBe(true)
    expect(pointTitles.some(title => title.endsWith(': 50'))).toBe(true)
    expect(pointTitles.some(title => title.endsWith(': 0'))).toBe(true)

    fireEvent.click(rangeControls.querySelector('button:nth-child(1)'))
    await waitFor(() => expect(rangeControls.querySelector('button:nth-child(1)')).toHaveClass('segment-active'))
    await waitFor(() => expect(api.endpointMetrics).toHaveBeenCalledTimes(2))
    await waitFor(() => expect(document.querySelectorAll('.traffic-panel svg circle')).toHaveLength(61))
    fireEvent.click(rangeControls.querySelector('button:nth-child(2)'))
    await waitFor(() => expect(rangeControls.querySelector('button:nth-child(2)')).toHaveClass('segment-active'))
    await waitFor(() => expect(api.endpointMetrics).toHaveBeenCalledTimes(3))
    await waitFor(() => expect(document.querySelectorAll('.traffic-panel svg circle')).toHaveLength(361))
    fireEvent.click(rangeControls.querySelector('button:nth-child(3)'))
    await waitFor(() => expect(rangeControls.querySelector('button:nth-child(3)')).toHaveClass('segment-active'))
    await waitFor(() => expect(api.endpointMetrics).toHaveBeenCalledTimes(4))
    await waitFor(() => expect(document.querySelectorAll('.traffic-panel svg circle')).toHaveLength(1441))

    cleanup()
    sessionStorage.clear()
    vi.restoreAllMocks()
  })
})

describe('pull request detail structured LLM review UI', () => {
  const structuredReview = {
    decision: 'CRITICAL',
    score: 96.0,
    summary: 'Removing return before JSX breaks component rendering.',
    findings: ['CRITICAL BUILD_BREAKING in src/Components/Github.jsx'],
    provider: 'groq',
    fallback: false,
    analysis: {
      overallAssessment: 'Removing return before JSX breaks component rendering control flow.',
      overallRisk: {
        level: 'CRITICAL',
        score: 96.0,
        confidence: 0.98,
        reason: 'React component no longer returns valid element.',
      },
      changeSummary: 'Component return keyword was stripped in JSX layout update.',
      recommendation: 'Restore return statement and verify JSX rendering in test suite.',
      reviewConfidence: 0.98,
      riskAlignment: {
        deterministicRisk: 'LOW',
        llmRisk: 'CRITICAL',
        alignment: 'DIFFERENT',
        explanation: 'Deterministic scoring did not include the syntax-breaking React return regression in the UI diff.',
      },
      businessImpact: {
        summary: 'Users visiting the GitHub integrations page will encounter a blank screen.',
        affectedCapability: 'GitHub integrations page',
        confidence: 0.95,
      },
      criticalFindings: [
        {
          severity: 'CRITICAL',
          category: 'BUILD_BREAKING',
          file: 'src/Components/Github.jsx',
          finding: 'Component return keyword removed before JSX block',
          whyItMatters: 'React component function will return undefined instead of JSX element',
          evidence: '- return (\n+ (',
          recommendedAction: 'Re-add return before the opening parenthesis',
        },
      ],
      files: [
        {
          file: 'src/Components/Github.jsx',
          changeSummary: 'Removed return keyword',
          whatChanged: '`return (` was replaced with `(`',
          whatItDoes: 'Renders the GitHub integration dashboard and tabs',
          technicalImpact: 'Component evaluates to undefined at runtime',
          businessImpact: 'Frontend crash on GitHub page visit',
          riskLevel: 'CRITICAL',
          riskCategories: ['BUILD_BREAKING', 'RUNTIME_BREAKING'],
          evidence: ['Diff line 7: - return (', 'Diff line 8: + ('],
          affectedEndpoints: [
            {
              method: 'GET',
              path: '/api/github/repos',
              criticality: 'HIGH',
              impact: 'Client will not render received repository payload',
            },
          ],
          confidence: 0.98,
        },
      ],
      endpointImpact: [
        {
          method: 'GET',
          path: '/api/github/repos',
          criticality: 'HIGH',
          impact: 'Data fetched by client is unrendered due to view failure',
          reason: 'Component view layer crashes during render lifecycle',
        },
      ],
    },
  }

  it('renders all structured review sections, contrast callouts, and file analyses', async () => {
    const payload = btoa(JSON.stringify({ sub: 'reviewer@example.com' }))
    setToken(`header.${payload}.signature`)

    vi.spyOn(api, 'projects').mockResolvedValue([{ id: 1, name: 'EndpointGuard Core' }])
    vi.spyOn(api, 'repositories').mockResolvedValue([])
    vi.spyOn(api, 'endpoints').mockResolvedValue([])
    vi.spyOn(api, 'health').mockResolvedValue({ status: 'UP' })
    vi.spyOn(api, 'pullRequest').mockResolvedValue({
      id: 42,
      projectId: 1,
      repositoryId: 10,
      repositoryName: 'acme/core-api',
      githubPrNumber: 42,
      title: 'Fix github layout syntax',
      author: 'dev-alice',
      status: 'OPEN',
      openedAt: '2026-10-01T10:00:00Z',
      changedFiles: [
        { id: 1, filePath: 'src/Components/Github.jsx', additions: 1, deletions: 1, patch: '- return (\n+ (' },
      ],
      affectedEndpoints: [
        { endpointId: 100, method: 'GET', pathPattern: '/api/github/repos', criticality: 'HIGH' },
      ],
      riskHistory: [
        {
          id: 501,
          score: 0.25,
          tier: 'LOW',
          evaluatedAt: '2026-10-01T10:05:00Z',
          evaluationStatus: 'COMPLETED',
          dataQualityNotes: ['Baseline traffic active'],
        },
      ],
      reviewStatus: 'COMPLETED',
      review: structuredReview,
    })

    render(<MemoryRouter initialEntries={['/pull-requests/42']}><App /></MemoryRouter>)

    // 1. Overall assessment & what changed
    await waitFor(() => {
      expect(screen.getByText(/Removing return before JSX breaks component rendering control flow/i)).toBeInTheDocument()
    })
    expect(screen.getByText(/Component return keyword was stripped in JSX layout update/i)).toBeInTheDocument()

    // 2. Risk comparison: Deterministic vs AI Advisory
    expect(screen.getByText('Deterministic System Risk vs AI Advisory Assessment')).toBeInTheDocument()
    expect(screen.getByText('AUTHORITATIVE GATE')).toBeInTheDocument()
    expect(screen.getByText('ADVISORY CONTEXT')).toBeInTheDocument()
    expect(screen.getByText(/0.25 score/i)).toBeInTheDocument()
    expect(screen.getByText('Scored 0.25')).toBeInTheDocument()

    // 3. AI Risk & Confidence
    expect(screen.getByText('98%')).toBeInTheDocument() // Confidence
    expect(screen.getByText(/Restore return statement and verify JSX rendering/i)).toBeInTheDocument() // Recommendation
    expect(screen.getByText(/AI advisory risk differs from deterministic system risk/i)).toBeInTheDocument()
    expect(screen.getByText(/Deterministic: LOW · AI: CRITICAL/i)).toBeInTheDocument()
    expect(screen.getByText(/Deterministic scoring did not include the syntax-breaking React return regression in the UI diff/i)).toBeInTheDocument()

    // 4. Business Impact
    expect(screen.getByText('GitHub integrations page')).toBeInTheDocument()
    expect(screen.getByText(/Users visiting the GitHub integrations page will encounter a blank screen/i)).toBeInTheDocument()

    // 5. Critical Finding
    expect(screen.getByText('Component return keyword removed before JSX block')).toBeInTheDocument()
    expect(screen.getByText(/React component function will return undefined instead of JSX element/i)).toBeInTheDocument()
    expect(screen.getAllByText(/- return \(\s*\+ \(/i).length).toBeGreaterThan(0) // Evidence appears in both patch and finding details
    expect(screen.getByText(/Re-add return before the opening parenthesis/i)).toBeInTheDocument() // Action

    // 6. File-by-file analysis
    expect(screen.getAllByText('src/Components/Github.jsx').length).toBeGreaterThan(0)
    expect(screen.getAllByText('Build Breaking').length).toBeGreaterThan(0) // Category tag appears in multiple sections
    expect(screen.getAllByText('Runtime Breaking').length).toBeGreaterThan(0) // Category tag appears in multiple sections
    expect(screen.getByText(/Renders the GitHub integration dashboard and tabs/i)).toBeInTheDocument() // What it does
    expect(screen.getByText(/Component evaluates to undefined at runtime/i)).toBeInTheDocument() // Technical impact

    // 7. Affected Endpoints
    expect(screen.getAllByText('/api/github/repos').length).toBeGreaterThan(0)
    expect(screen.getByText(/Data fetched by client is unrendered due to view failure/i)).toBeInTheDocument()

    cleanup()
    sessionStorage.clear()
    vi.restoreAllMocks()
  })

  it('gracefully renders fallback review when structured analysis is absent', async () => {
    const payload = btoa(JSON.stringify({ sub: 'reviewer@example.com' }))
    setToken(`header.${payload}.signature`)

    vi.spyOn(api, 'projects').mockResolvedValue([{ id: 1, name: 'EndpointGuard Core' }])
    vi.spyOn(api, 'repositories').mockResolvedValue([])
    vi.spyOn(api, 'endpoints').mockResolvedValue([])
    vi.spyOn(api, 'health').mockResolvedValue({ status: 'UP' })
    vi.spyOn(api, 'pullRequest').mockResolvedValue({
      id: 43,
      projectId: 1,
      repositoryId: 10,
      repositoryName: 'acme/core-api',
      githubPrNumber: 43,
      title: 'Update readme',
      author: 'dev-bob',
      status: 'OPEN',
      openedAt: '2026-10-01T10:00:00Z',
      changedFiles: [],
      affectedEndpoints: [],
      riskHistory: [],
      reviewStatus: 'FALLBACK',
      review: {
        decision: 'LOW',
        score: 0.0,
        summary: 'Deterministic fallback review because Groq provider was unavailable',
        findings: ['Provider unavailable', 'Manual verification suggested'],
        provider: 'groq',
        fallback: true,
        analysis: null,
      },
    })

    render(<MemoryRouter initialEntries={['/pull-requests/43']}><App /></MemoryRouter>)

    await waitFor(() => {
      expect(screen.getByText(/Deterministic fallback review because Groq provider was unavailable/i)).toBeInTheDocument()
    })
    expect(screen.getByText('Provider unavailable')).toBeInTheDocument()
    expect(screen.getAllByText(/deterministic fallback/i).length).toBeGreaterThan(0)

    cleanup()
    sessionStorage.clear()
    vi.restoreAllMocks()
  })
})