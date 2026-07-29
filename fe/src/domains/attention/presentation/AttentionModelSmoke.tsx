"use client";

import { useEffect, useState } from "react";

import { createAttentionModel, type AttentionModel } from "../infrastructure/attentionModel";

type SmokeState =
  | { readonly status: "loading"; readonly probabilities: readonly number[] }
  | { readonly status: "ready"; readonly probabilities: readonly number[] }
  | { readonly status: "error"; readonly probabilities: readonly number[]; readonly error: string };

export function AttentionModelSmoke() {
  const [state, setState] = useState<SmokeState>({ status: "loading", probabilities: [] });

  useEffect(() => {
    let cancelled = false;

    async function runSmoke() {
      let model: AttentionModel | null = null;
      let probabilities: readonly number[] | null = null;
      let failure: unknown = null;
      try {
        model = await createAttentionModel();
        const prediction = await model.predict(new Float32Array(20 * 98));
        probabilities = prediction.probabilities;
      } catch (error) {
        failure = error;
      } finally {
        if (model) {
          try {
            await model.dispose();
          } catch (error) {
            failure ??= error;
          }
        }
      }

      if (!cancelled) {
        if (failure || !probabilities) {
          setState({
            status: "error",
            probabilities: [],
            error: failure instanceof Error ? failure.message : String(failure),
          });
        } else {
          setState({ status: "ready", probabilities });
        }
      }
    }

    void runSmoke();
    return () => {
      cancelled = true;
    };
  }, []);

  return (
    <output
      data-testid="attention-model-smoke"
      data-status={state.status}
      data-probabilities={JSON.stringify(state.probabilities)}
    >
      {state.status === "error" ? state.error : state.status}
    </output>
  );
}
