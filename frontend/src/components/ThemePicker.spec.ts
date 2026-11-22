// @vitest-environment jsdom
import { describe, expect, it } from "vitest";
import ThemePicker from "./ThemePicker.vue";
import StylePicker from "./StylePicker.vue";
import { mountWithVuetify } from "@/test/mountWithVuetify";
import { ART_STYLES, CUSTOM_THEME_ID, PREDEFINED_THEMES } from "@/data/adventureThemes";

function mountThemes(selected: string | null = null) {
  return mountWithVuetify(ThemePicker, {
    props: {
      themes: PREDEFINED_THEMES,
      selected,
      customTheme: "",
      customThemeError: "",
      validating: false,
    },
  });
}

describe("ThemePicker", () => {
  it("offers every theme plus a custom one, and marks the selected theme", () => {
    const wrapper = mountThemes("space");
    const cards = wrapper.findAll(".theme-card");
    expect(cards).toHaveLength(PREDEFINED_THEMES.length + 1);
    expect(wrapper.find(".theme-card.selected").text()).toContain("Space Frontier");
  });

  it("emits the id of the clicked theme", async () => {
    const wrapper = mountThemes();
    await wrapper.findAll(".theme-card")[1].trigger("click");
    await wrapper.find(".custom-theme").trigger("click");
    expect(wrapper.emitted("select")).toEqual([[PREDEFINED_THEMES[1].id], [CUSTOM_THEME_ID]]);
  });

  it("asks for a description only for the custom theme", async () => {
    const wrapper = mountThemes("space");
    expect(wrapper.find(".custom-theme-input").exists()).toBe(false);
    await wrapper.setProps({ selected: CUSTOM_THEME_ID });
    const input = wrapper.find(".custom-theme-input input");
    await input.setValue("A steampunk airship");
    await input.trigger("blur");
    expect(wrapper.emitted("update:customTheme")).toEqual([["A steampunk airship"]]);
    expect(wrapper.emitted("validate")).toHaveLength(1);
  });

  it("shows the validation error", async () => {
    const wrapper = mountThemes(CUSTOM_THEME_ID);
    await wrapper.setProps({ customThemeError: "Please provide a more detailed description" });
    expect(wrapper.find(".custom-theme-input").text()).toContain("Please provide a more detailed description");
  });
});

describe("StylePicker", () => {
  it("shows each style's sample and emits the clicked style", async () => {
    const wrapper = mountWithVuetify(StylePicker, { props: { styles: ART_STYLES, selected: "comic" } });
    expect(wrapper.findAll(".style-card")).toHaveLength(ART_STYLES.length);
    expect(wrapper.find(".style-card.selected").text()).toContain("Comic/Graphic Novel");
    expect(wrapper.find(".style-sample-image").attributes("src")).toBe(ART_STYLES[0].sampleImage);
    await wrapper.findAll(".style-card")[0].trigger("click");
    expect(wrapper.emitted("select")).toEqual([[ART_STYLES[0].id]]);
  });

  it("falls back to the gradient without a sample image", () => {
    const style = { id: "plain", name: "Plain", description: "", gradient: "red" };
    const wrapper = mountWithVuetify(StylePicker, { props: { styles: [style], selected: null } });
    expect(wrapper.find(".style-sample-image").exists()).toBe(false);
    expect(wrapper.find(".style-gradient").attributes("style")).toContain("red");
  });
});
