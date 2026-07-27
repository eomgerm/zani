/**
 * MediaRecorder WebM 스트림의 EBML 헤더 경계 탐지.
 *
 * MediaRecorder.start(timeslice)의 첫 dataavailable 조각은
 * [EBML 헤더 + Segment 정보][첫 Cluster(첫 오디오)]가 한 blob으로 붙어 나온다.
 * 헤더는 이후 어떤 클립을 만들든 맨 앞에 있어야 디코딩이 가능하므로 링버퍼에
 * 고정(pin)해야 하고, 첫 Cluster 의 오디오는 일반 조각처럼 시간이 지나면
 * 만료돼야 한다. 경계를 분리하지 않으면 업로드마다 "수업 맨 처음 몇 초"가
 * 클립 앞에 붙어 전사를 오염시킨다.
 */

/** Matroska Cluster 요소 ID (4바이트). 이 위치가 헤더와 오디오의 경계다. */
const CLUSTER_ID = [0x1f, 0x43, 0xb6, 0x75] as const;

/**
 * Cluster 의 첫 자식 요소로 오는 Timecode 요소 ID. 오디오 payload 안에서 우연히
 * Cluster ID 와 같은 4바이트가 나오는 오탐을 거르는 검증에 쓴다.
 */
const TIMECODE_ID = 0xe7;

/**
 * EBML 가변 길이 정수(vint)의 전체 길이(바이트)를 첫 바이트로 판정한다.
 * 첫 세트 비트의 위치가 길이를 나타내며, 0x00 은 유효한 vint 첫 바이트가 아니다.
 */
function vintLength(firstByte: number): number {
  for (let length = 1, mask = 0x80; length <= 8; length += 1, mask >>= 1) {
    if (firstByte & mask) {
      return length;
    }
  }
  return 0;
}

/**
 * 첫 Cluster 가 시작하는 오프셋을 찾는다. 못 찾으면 -1.
 *
 * 검증: Cluster ID 뒤에는 사이즈 vint(스트리밍 WebM 은 unknown-size 포함),
 * 그 뒤에는 Timecode(0xE7)가 와야 한다. 검증에 필요한 바이트가 조각 끝에 걸려
 * 부족한 경우도 오탐 가능성으로 보고 건너뛴다 — 그 결과 -1 이 되면 호출자는
 * 조각 전체를 헤더로 고정하며, 이때 잃는 오디오는 검증 불가한 극소량뿐이다.
 */
export function findFirstClusterOffset(bytes: Uint8Array): number {
  for (let i = 0; i + CLUSTER_ID.length <= bytes.length; i += 1) {
    if (
      bytes[i] !== CLUSTER_ID[0] ||
      bytes[i + 1] !== CLUSTER_ID[1] ||
      bytes[i + 2] !== CLUSTER_ID[2] ||
      bytes[i + 3] !== CLUSTER_ID[3]
    ) {
      continue;
    }

    const sizePos = i + CLUSTER_ID.length;
    if (sizePos >= bytes.length) {
      continue;
    }
    const sizeLength = vintLength(bytes[sizePos]);
    if (sizeLength === 0) {
      continue;
    }
    const timecodePos = sizePos + sizeLength;
    if (timecodePos >= bytes.length) {
      continue;
    }
    if (bytes[timecodePos] === TIMECODE_ID) {
      return i;
    }
  }
  return -1;
}

export interface SplitWebmChunk {
  /** 클립마다 맨 앞에 재사용할 초기화 구간(EBML 헤더~첫 Cluster 직전). */
  readonly header: Blob;
  /** 첫 Cluster 부터의 오디오. Cluster 를 찾지 못하면 null(조각 전체가 헤더). */
  readonly body: Blob | null;
}

/** 첫 dataavailable 조각을 헤더와 오디오 본문으로 나눈다. Blob.slice 라 복사 비용이 없다. */
export async function splitFirstWebmChunk(chunk: Blob): Promise<SplitWebmChunk> {
  const bytes = new Uint8Array(await chunk.arrayBuffer());
  const offset = findFirstClusterOffset(bytes);
  if (offset < 0) {
    return { header: chunk, body: null };
  }
  return {
    header: chunk.slice(0, offset, chunk.type),
    body: chunk.slice(offset, chunk.size, chunk.type),
  };
}
