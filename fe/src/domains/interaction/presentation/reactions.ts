/**
 * 반응 종류와 그림.
 *
 * 서버는 이모지 문자가 아니라 종류 이름을 주고받는다 — 받은 문자열이 전 참가자 화면에 그대로 뜨므로
 * 임의 문자열을 허용할 수 없고, 같은 하트라도 변이 선택자(U+FE0F) 유무로 리포트 집계가 갈린다.
 * 그래서 어떤 그림으로 보일지는 이 파일이 정한다. 디자인이 바뀌어도 쌓인 기록의 의미는 그대로다.
 */

export const REACTION_KINDS = ["LIKE", "HEART", "CLAP", "CELEBRATE", "WOW", "CHEER"] as const;

export type ReactionKind = (typeof REACTION_KINDS)[number];

export const REACTION_EMOJI: Record<ReactionKind, string> = {
  LIKE: "👍",
  HEART: "❤️",
  CLAP: "👏",
  CELEBRATE: "🎉",
  WOW: "😮",
  CHEER: "🙌",
};

/**
 * 아는 종류인지 좁힌다.
 *
 * `in` 이나 `REACTION_EMOJI[value] !== undefined` 로 검사하면 안 된다 — 둘 다 프로토타입 체인을 타서
 * `toString`·`constructor` 같은 상속 키가 통과하고, 이모지 자리에 함수가 실려 나간다.
 */
function isReactionKind(value: string): value is ReactionKind {
  return (REACTION_KINDS as readonly string[]).includes(value);
}

/** 모르는 값은 그리지 않는다. 서버가 걸러주지만 배포 시점이 어긋나면 새 종류가 먼저 올 수 있다. */
export function reactionEmojiOf(reaction: string): string | null {
  return isReactionKind(reaction) ? REACTION_EMOJI[reaction] : null;
}
