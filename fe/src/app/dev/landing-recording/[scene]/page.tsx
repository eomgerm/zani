import {
  LandingRecordingRoom,
  type LandingRecordingScene,
} from "@/domains/lecture";
import { notFound } from "next/navigation";

const scenes: readonly LandingRecordingScene[] = [
  "live-classroom",
  "browser-analysis",
  "student-prompt",
  "instructor-tip",
];

export default async function Page({
  params,
}: {
  params: Promise<{ scene: string }>;
}) {
  const { scene } = await params;
  if (!scenes.includes(scene as LandingRecordingScene)) notFound();

  return <LandingRecordingRoom scene={scene as LandingRecordingScene} />;
}
