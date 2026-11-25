// @vitest-environment jsdom
import { describe, expect, it, vi } from "vitest";
import MessageList from "./MessageList.vue";
import { mountWithVuetify } from "@/test/mountWithVuetify";
import type { GameMessage } from "@/composables/useWebSocketGame";

const at = new Date(0);

function mountList(messages: GameMessage[], loading = false) {
  return mountWithVuetify(MessageList, { props: { messages, loading } });
}

describe("MessageList", () => {
  it("shows commands with a prompt and responses as they are", () => {
    const wrapper = mountList([
      { text: "look", isUser: true, timestamp: at },
      { text: "You are in a cave.", isUser: false, timestamp: at },
    ]);
    const [command, response] = wrapper.findAll(".game-message");
    expect(command.classes()).toContain("user");
    expect(command.text()).toBe("> look");
    expect(response.classes()).toContain("game");
    expect(response.text()).toBe("You are in a cave.");
  });

  it("says the game is opening until there is something to show", async () => {
    const wrapper = mountWithVuetify(MessageList, {
      props: { messages: [], loading: false, opening: "Starting your adventure..." },
    });
    expect(wrapper.find(".opening-indicator").text()).toBe("Starting your adventure...");

    await wrapper.setProps({ messages: [{ text: "You are in a cave.", isUser: false, timestamp: at }] });
    expect(wrapper.find(".opening-indicator").exists()).toBe(false);
  });

  it("enlarges a scene picture when it is clicked", async () => {
    const wrapper = mountWithVuetify(MessageList, {
      props: {
        messages: [{ text: "A hall", isUser: false, timestamp: at, image: "/pictures/hall.jpg" }],
        loading: false,
      },
      attachTo: document.body,
    });
    // The dialog positions itself by the visual viewport, which jsdom does not have
    vi.stubGlobal("visualViewport", new EventTarget());
    expect(document.querySelector(".scene-image-enlarged")).toBeNull();

    await wrapper.find("img.scene-image").trigger("click");
    expect(document.querySelector(".scene-image-enlarged")?.getAttribute("src")).toBe("/pictures/hall.jpg");
    wrapper.unmount();
    vi.unstubAllGlobals();
  });

  it("lists a scene's exits", () => {
    const wrapper = mountList([
      {
        text: "A crossroads.",
        isUser: false,
        timestamp: at,
        scene: {
          locationName: "Crossroads",
          exits: [
            { direction: "north", description: "to the forest" },
            { direction: "east", description: "" },
          ],
          items: [],
          npcs: [],
        },
      },
    ]);
    expect(wrapper.find(".exits-info").text()).toBe("Exits:north (to the forest),east");
  });

  it("shows a streaming cursor, then the scene image or its spinner", () => {
    const wrapper = mountList([
      { text: "Stream", isUser: false, timestamp: at, isStreaming: true },
      { text: "Loading", isUser: false, timestamp: at, imageLoading: true },
      { text: "Done", isUser: false, timestamp: at, image: "data:image/png;base64,aW1hZ2U=" },
    ]);
    const [streaming, loading, done] = wrapper.findAll(".game-message");
    expect(streaming.find(".streaming-cursor").exists()).toBe(true);
    expect(streaming.find(".scene-image-container").exists()).toBe(false);
    expect(loading.find(".image-loading").exists()).toBe(true);
    expect(done.find("img.scene-image").attributes("src")).toBe("data:image/png;base64,aW1hZ2U=");
  });

  it("shows Thinking... while waiting for a response", async () => {
    const wrapper = mountList([], true);
    expect(wrapper.find(".loading-indicator").text()).toContain("Thinking...");
    await wrapper.setProps({ loading: false });
    expect(wrapper.find(".loading-indicator").exists()).toBe(false);
  });

  it("scrolls to the latest message", async () => {
    const wrapper = mountList([{ text: "One", isUser: false, timestamp: at }]);
    const output = wrapper.find(".game-output").element as HTMLElement;
    Object.defineProperty(output, "scrollHeight", { value: 500 });
    await (wrapper.vm as unknown as { scrollToBottom: () => Promise<void> }).scrollToBottom();
    expect(output.scrollTop).toBe(500);
  });
});
