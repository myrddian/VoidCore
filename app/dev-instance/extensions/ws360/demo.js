voidcore.registerScreen({
  onEnter(ctx) {
    const previous = ctx.getForCurrentUser("visit-counter") ?? { count: 0 };
    const count = Number(previous.count ?? 0) + 1;
    const notes = ctx.docsByType("note", 5) ?? [];

    ctx.putForCurrentUser("visit-counter", { count });
    ctx.banner("WS/360 DEMO");
    ctx.render(
      ctx.el.vstack([
        ctx.el.header("WS/360 // EXTENSION DEMO", "JS HOST"),
        ctx.el.para(
          "This screen is discovered from /instance/extensions, rendered by GraalJS, " +
          "and pushed from the main menu through the custom-screen registry.",
          "default"
        ),
        ctx.el.rule(),
        ctx.el.text("Current route: " + (ctx.session.currentRoute ?? "<none>"), "bright_cyan"),
        ctx.el.text("Visits for this user: " + count, "bright_green"),
        ctx.el.text("Readable note docs detected: " + notes.length, "bright_yellow"),
        ctx.el.spacer(1),
        ctx.el.keyMenu([
          ctx.el.keyEntry("C", "Set WS/360 theme"),
          ctx.el.keyEntry("Q", "Back to menu")
        ])
      ], 1),
      null
    );
    ctx.promptKeystroke("ws360:", "CQ");
  },

  onKey(ctx, key) {
    if (key === "C") {
      ctx.setTheme("ws360");
      ctx.notifyMain("WS/360 theme armed. Hit [T] on the menu to keep cycling through it.", "info", 2200);
      this.onEnter(ctx);
      return;
    }
    if (key === "Q") {
      ctx.pop();
    }
  }
});
