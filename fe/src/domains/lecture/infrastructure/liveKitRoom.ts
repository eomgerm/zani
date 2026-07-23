import { Room } from "livekit-client";

export type LiveKitRoomFactory = () => Room;

export const createLiveKitRoom: LiveKitRoomFactory = () => new Room();
