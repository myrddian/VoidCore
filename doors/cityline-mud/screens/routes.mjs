import { createOptionScreen as createSdkOptionScreen } from "@voidcore/door-sdk";
import { renderOptionMenu } from "../render.mjs";
import { ROOMS } from "../world.mjs";
import { move } from "../index.mjs";

function routeOptionsForRoomKey(roomKey) {
  const room = ROOMS[roomKey];
  return room.exits.map((exit, index) => ({
    key: String(index + 1),
    label: ROOMS[exit].name,
    aliases: [exit, ROOMS[exit].name],
    value: exit,
    async action(session, { stack, option }) {
      await stack.pop(session, "route-picked");
      await move(session, option.value);
    }
  }));
}

export function createRoutesScreen() {
  return createSdkOptionScreen({
    id: "routes-menu",
    promptLabel: "[esc] route:",
    initialNote: "Pick a route. Number, room name, or alias all work.",
    onUnknown(session, input, { paint }) {
      paint(session, `No route matches: ${input}`);
    },
    render(session, view) {
      const room = ROOMS[session.data.profile.room];
      const options = view.options.map((option) => ({
        ...option,
        summary: ROOMS[option.value].description,
        meta: `route: ${ROOMS[option.value].name}`
      }));
      return renderOptionMenu(session, {
        title: `ROUTES://${room.key.toUpperCase()}`,
        subtitle: `${room.name} exits and nearby channels`,
        options,
        note: view.note
      });
    },
    options: [{ key: "-", label: "loading...", action: async () => {} }],
    async onEnter(session, ctx) {
      ctx.options.splice(0, ctx.options.length, ...routeOptionsForRoomKey(session.data.profile.room));
      ctx.paint(session);
    }
  });
}
