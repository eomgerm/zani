import { NoteScreen } from "@/domains/lecture";

export default async function Page({
  params,
}: {
  params: Promise<{ sessionId: string }>;
}) {
  const { sessionId } = await params;
  return <NoteScreen lectureId={sessionId} />;
}
