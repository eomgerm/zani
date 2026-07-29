import { expect, test } from '@playwright/test';

test('production build loads the tracked ONNX model and completes one browser inference', async ({
  page,
}) => {
  const artifactResponses = new Map<string, number>();
  page.on('response', (response) => {
    const url = new URL(response.url());
    if (
      url.pathname === '/attention/models/engagement.onnx' ||
      url.pathname === '/attention/models/engagement.metadata.json'
    ) {
      artifactResponses.set(url.pathname, response.status());
    }
  });

  await page.goto('/attention-model-smoke');

  const result = page.getByTestId('attention-model-smoke');
  await expect(result).toHaveAttribute('data-status', 'ready', { timeout: 30_000 });
  const probabilities = JSON.parse((await result.getAttribute('data-probabilities')) ?? '[]') as
    number[];

  expect(probabilities).toHaveLength(4);
  expect(probabilities.every(Number.isFinite)).toBe(true);
  expect(probabilities.reduce((sum, value) => sum + value, 0)).toBeCloseTo(1, 5);
  expect(artifactResponses.get('/attention/models/engagement.onnx')).toBe(200);
  expect(artifactResponses.get('/attention/models/engagement.metadata.json')).toBe(200);
});
