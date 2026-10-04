import { createHmac, randomUUID } from 'node:crypto'
import { expect, test } from '@playwright/test'

const webhookSecret = process.env.E2E_WEBHOOK_SECRET || 'endpointguard-local-e2e-secret'
let cleanupProjectId
let cleanupToken

test.afterEach(async ({ request }) => {
  if (!cleanupProjectId || !cleanupToken) return
  const headers = { Authorization: `Bearer ${cleanupToken}` }
  const repositoriesResponse = await request.get(`/api/projects/${cleanupProjectId}/repositories`, { headers })
  if (repositoriesResponse.ok()) {
    for (const repository of await repositoriesResponse.json()) {
      await request.delete(`/api/projects/${cleanupProjectId}/repositories/${repository.id}`, { headers })
    }
  }
  cleanupProjectId = undefined
  cleanupToken = undefined
})

test('signed pull request webhook appears with risk, code changes, and review', async ({ page, baseURL }) => {
  const suffix = randomUUID().slice(0, 8)
  const email = `browser-${suffix}@example.com`
  const projectName = `Browser E2E ${suffix}`
  const repositoryName = process.env.E2E_GITHUB_REPOSITORY || 'octocat/Hello-World'

  await page.goto('/login')
  await page.getByRole('button', { name: 'Create an account' }).click()
  await page.getByLabel('Work email').fill(email)
  await page.getByLabel('Password').fill('e2e-password-123')
  await page.getByRole('button', { name: 'Create account' }).click()
  await expect(page.getByRole('heading', { name: 'Production risk overview' })).toBeVisible()

  await page.getByRole('link', { name: 'Projects' }).click()
  await page.getByLabel('Project name').fill(projectName)
  await page.getByRole('button', { name: 'Create project' }).click()
  await expect(page.locator('.inline-success')).toContainText(projectName)

  const token = await page.evaluate(() => sessionStorage.getItem('endpointguard.jwt'))
  cleanupToken = token
  const projectsResponse = await page.request.get(`${baseURL}/api/projects`, {
    headers: { Authorization: `Bearer ${token}` },
  })
  expect(projectsResponse.ok()).toBeTruthy()
  const projects = await projectsResponse.json()
  const project = projects.find(item => item.name === projectName)
  expect(project).toBeDefined()
  cleanupProjectId = project.id

  await page.getByLabel('GitHub repository').fill(repositoryName)
  await page.getByLabel('Webhook secret reference').fill('E2E_GITHUB_WEBHOOK_SECRET')
  await page.getByRole('button', { name: 'Link repository' }).click()
  await expect(page.getByText(repositoryName, { exact: true })).toBeVisible()

  await page.getByRole('link', { name: 'Endpoints' }).click()
  await page.getByRole('button', { name: 'Register endpoint' }).first().click()
  await page.getByLabel('Method').selectOption('GET')
  await page.getByPlaceholder('/api/orders/{id}').fill('/api/orders')
  await page.locator('.endpoint-form textarea').fill('**/OrderController.java')
  await page.locator('.endpoint-form').getByRole('button', { name: 'Register endpoint' }).click()
  await expect(page.getByText('/api/orders', { exact: true })).toBeVisible()

  const payload = JSON.stringify({
    action: 'opened',
    repository: { full_name: repositoryName },
    pull_request: {
      number: 42,
      title: 'Update order response contract',
      user: { login: 'browser-e2e' },
      state: 'open',
      created_at: new Date().toISOString(),
      head: { sha: `e2e-${suffix}` },
    },
    files: [{
      filename: 'src/main/java/OrderController.java',
      additions: 3,
      deletions: 1,
      patch: '@@ -1,1 +1,3 @@\n-old response\n+new response\n+validate currency',
    }],
  })
  const signature = `sha256=${createHmac('sha256', webhookSecret).update(payload).digest('hex')}`
  const webhookResponse = await page.request.post(`${baseURL}/api/webhooks/github`, {
    data: payload,
    headers: {
      'Content-Type': 'application/json',
      'X-GitHub-Event': 'pull_request',
      'X-GitHub-Delivery': `browser-e2e-${suffix}`,
      'X-Hub-Signature-256': signature,
    },
  })
  expect(webhookResponse.status()).toBe(202)

  await page.getByRole('link', { name: 'Pull requests' }).click()
  const pullRequestLink = page.getByRole('link', { name: /#42/ })
  await expect(pullRequestLink).toBeVisible()
  await pullRequestLink.click()

  await expect(page.getByRole('heading', { name: 'Update order response contract' })).toBeVisible()
  await expect(page.getByText('src/main/java/OrderController.java', { exact: true })).toBeVisible()
  await page.getByText('View patch').click()
  await expect(page.getByText('validate currency')).toBeVisible()
  await expect(page.getByText('/api/orders', { exact: true })).toBeVisible()
  await expect(page.getByText('INSUFFICIENT_DATA').first()).toBeVisible()
  await expect(page.getByRole('heading', { name: 'LLM review' })).toBeVisible()
  await expect(page.getByText('FALLBACK', { exact: true })).toBeVisible()
  await expect(page.locator('.review-summary')).toContainText(/using deterministic fallback/)
})