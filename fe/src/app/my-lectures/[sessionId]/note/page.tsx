import { InstructorNoteEditor } from "@/domains/report";

export default async function Page({
  params,
}: {
  params: Promise<{ sessionId: string }>;
}) {
  const { sessionId } = await params;
  return <InstructorNoteEditor sessionId={sessionId} />;
}
