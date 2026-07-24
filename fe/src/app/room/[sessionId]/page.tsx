import { RoomScreen } from "@/domains/lecture";

export default async function Page({ params }: { params: Promise<{ sessionId: string }> }) {
  const { sessionId } = await params;

  return <RoomScreen sessionId={sessionId} />;
}
