import { PrejoinScreen } from "@/domains/lecture";

export default async function Page({
  params,
}: {
  params: Promise<{ inviteCode: string }>;
}) {
  const { inviteCode } = await params;
  return <PrejoinScreen inviteCode={inviteCode} />;
}
