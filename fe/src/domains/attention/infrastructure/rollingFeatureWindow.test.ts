import { describe, expect, it } from "vitest";

import { RollingFeatureWindow } from "./rollingFeatureWindow";

function values(value: number): Float32Array {
  return new Float32Array(49).fill(value);
}

describe("RollingFeatureWindow", () => {
  it("builds 20 mean/std tokens from the latest ten seconds", () => {
    const window = new RollingFeatureWindow();
    for (let timestamp = 0; timestamp < 10_000; timestamp += 100) {
      window.add(timestamp, values(Math.floor(timestamp / 500)));
    }

    const tokens = window.tokens(10_000);

    expect(tokens).not.toBeNull();
    expect(tokens).toHaveLength(20 * 98);
    expect(tokens?.[0]).toBe(0);
    expect(tokens?.[98]).toBe(1);
    expect(tokens?.[49]).toBe(0);
  });

  it("rejects a segment with fewer than three detected faces", () => {
    const window = new RollingFeatureWindow();
    for (let timestamp = 0; timestamp < 10_000; timestamp += 100) {
      window.add(timestamp, timestamp < 300 ? null : values(1));
    }

    expect(window.tokens(10_000)).toBeNull();
  });

  it("drops frames older than the rolling horizon", () => {
    const window = new RollingFeatureWindow();
    window.add(0, values(1));
    window.add(11_000, values(2));

    expect(window.size).toBe(1);
  });
});
