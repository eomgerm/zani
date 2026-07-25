export function columnMajorTransformToRowMajor(matrix: readonly number[]): number[] {
  if (matrix.length !== 16) throw new Error("4x4 얼굴 변환 행렬이 필요합니다.");
  return Array.from({ length: 16 }, (_, index) => {
    const row = Math.floor(index / 4);
    const column = index % 4;
    return matrix[column * 4 + row] as number;
  });
}
