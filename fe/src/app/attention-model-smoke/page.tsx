import type { Metadata } from "next";

import { AttentionModelSmoke } from "@/domains/attention";

export const metadata: Metadata = {
  robots: { index: false, follow: false },
};

export default function AttentionModelSmokePage() {
  return <AttentionModelSmoke />;
}
