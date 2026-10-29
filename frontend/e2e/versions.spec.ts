import { expect, test, type Page } from '@playwright/test'

// Runs against the dev stack (E2E_BASE_URL): publish with a reviewer, then restore an older version and publish it.
// It adds versions to the port, so it is for a stack that resets on start.
test.skip(!process.env.E2E_BASE_URL, 'needs the running dev stack: set E2E_BASE_URL')

async function openVersions(page: Page, user: string) {
  await page.goto('/login')
  await page.getByLabel('Choose a demo user').selectOption(user)
  await page.getByRole('button', { name: 'Sign in' }).click()
  await expect(page.getByRole('main', { name: 'Port stage' })).toBeVisible({ timeout: 20_000 })
  await page.getByRole('button', { name: 'Design' }).click()
  await page.getByRole('tab', { name: 'Versions' }).click()
  return page.getByRole('region', { name: 'Versions' })
}

test('an editor sees the Versions tab but cannot publish', async ({ page }) => {
  const tab = await openVersions(page, 'editor')

  await expect(tab.getByText('Published', { exact: true })).toBeVisible({ timeout: 20_000 })
  await expect(tab.getByRole('button', { name: 'Publish' })).toBeDisabled()
  await expect(tab.getByRole('button', { name: 'Discard draft' })).toHaveCount(0)
})

test('a publisher publishes with a reviewer, restores v1 and publishes it again with the same asset ids', async ({
  page,
}) => {
  const tab = await openVersions(page, 'publisher')
  const table = tab.getByRole('table', { name: 'Published versions' })
  await expect(table).toBeVisible({ timeout: 20_000 })
  const liveBefore = Number(
    (await table.getByRole('row').nth(1).getByRole('strong').textContent())!.slice(1),
  )

  // publish the open draft, reviewed by the admin
  await tab.getByPlaceholder('What changed').fill('e2e publish')
  await tab.getByRole('button', { name: 'Publish' }).click()
  const dialog = page.getByRole('alertdialog')
  await dialog.getByLabel('Reviewed by (optional)').selectOption('admin')
  await dialog.getByRole('button', { name: 'Publish' }).click()
  await expect(
    page.getByText(`Version ${liveBefore + 1} published. Monitor shows it now.`),
  ).toBeVisible()
  const first = table.getByRole('row').nth(1)
  await expect(first).toContainText(`v${liveBefore + 1}`)
  await expect(first).toContainText('live')
  await expect(first).toContainText('reviewed by admin')
  await expect(first).toContainText('e2e publish')
  await expect(first).toContainText('publisher')

  // restore v1: a draft based on v1 appears; publishing it makes the next version, "restored from v1"
  await tab.getByRole('button', { name: 'Restore version 1', exact: true }).click()
  await expect(
    page.getByText('Load version 1 into a new draft? Asset IDs stay the same.'),
  ).toBeVisible()
  await page.getByRole('alertdialog').getByRole('button', { name: 'Restore' }).click()
  await expect(
    page.getByText('Version 1 loaded. Asset IDs are unchanged. Publish to make it live.'),
  ).toBeVisible()
  await expect(tab.getByText('Draft (based on v1)').first()).toBeVisible()
  await tab.getByRole('button', { name: 'Publish' }).click()
  await page.getByRole('alertdialog').getByRole('button', { name: 'Publish' }).click()
  // the draft started after the first publish is discarded by the restore, so the number is not liveBefore + 2
  await expect(page.getByText(/^Version \d+ published\. Monitor shows it now\.$/)).toBeVisible()
  await expect(table.getByRole('row').nth(1)).toContainText('restored from v1')
})
