/*
 * chesscraft
 *
 * Copyright (c) 2023 Jason Penilla
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package xyz.jpenilla.chesscraft.command;

import cloud.commandframework.Command;
import cloud.commandframework.arguments.CommandArgument;
import cloud.commandframework.arguments.flags.FlagContext;
import cloud.commandframework.arguments.parser.ArgumentParseResult;
import cloud.commandframework.arguments.standard.EnumArgument;
import cloud.commandframework.arguments.standard.StringArgument;
import cloud.commandframework.brigadier.CloudBrigadierManager;
import cloud.commandframework.bukkit.arguments.selector.SinglePlayerSelector;
import cloud.commandframework.bukkit.parsers.MaterialArgument;
import cloud.commandframework.bukkit.parsers.selector.SinglePlayerSelectorArgument;
import cloud.commandframework.exceptions.CommandExecutionException;
import cloud.commandframework.execution.CommandExecutionCoordinator;
import cloud.commandframework.minecraft.extras.AudienceProvider;
import cloud.commandframework.minecraft.extras.MinecraftExceptionHandler;
import cloud.commandframework.paper.PaperCommandManager;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiConsumer;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.checkerframework.checker.nullness.qual.Nullable;
import xyz.jpenilla.chesscraft.BoardManager;
import xyz.jpenilla.chesscraft.ChessBoard;
import xyz.jpenilla.chesscraft.ChessCraft;
import xyz.jpenilla.chesscraft.ChessGame;
import xyz.jpenilla.chesscraft.ChessPlayer;
import xyz.jpenilla.chesscraft.data.PVPChallenge;
import xyz.jpenilla.chesscraft.data.Vec3;
import xyz.jpenilla.chesscraft.data.piece.PieceColor;
import xyz.jpenilla.chesscraft.data.piece.PieceType;
import xyz.jpenilla.chesscraft.util.ComponentRuntimeException;

public final class Commands {
  private static final Set<PieceType> VALID_PROMOTIONS = Set.of(PieceType.BISHOP, PieceType.KNIGHT, PieceType.QUEEN, PieceType.ROOK);

  private final ChessCraft plugin;
  private final PaperCommandManager<CommandSender> mgr;
  private final BoardManager boardManager;

  public Commands(final ChessCraft plugin) {
    this.plugin = plugin;
    this.boardManager = plugin.boardManager();
    this.mgr = createCommandManager(plugin);
  }

  public void register() {
    this.plugin.getServer().getScheduler().runTaskTimerAsynchronously(this.plugin, this.boardManager.challenges()::cleanUp, 20L, 20L * 60L);

    final Command.Builder<CommandSender> chess = this.mgr.commandBuilder("chess");

    this.mgr.command(chess.literal("version")
      .handler(ctx -> {
        ctx.getSender().sendRichMessage("<bold><black>Chess<white>Craft");
        ctx.getSender().sendRichMessage("<gray><italics>  v" + this.plugin.getDescription().getVersion());
      }));

    this.mgr.command(chess.literal("reload")
      .handler(ctx -> {
        this.plugin.reloadMainConfig();
        this.boardManager.reload();
        ctx.getSender().sendRichMessage("<green>Reloaded configs.");
      }));

    this.mgr.command(chess.literal("create_board")
      .argument(StringArgument.single("name"))
      .senderType(Player.class)
      .handler(ctx -> {
        final String name = ctx.get("name");
        final Player sender = (Player) ctx.getSender();
        if (this.boardManager.board(name) != null) {
          sender.sendMessage(this.plugin.config().messages().boardAlreadyExists(name));
          return;
        }
        this.boardManager.createBoard(
          name,
          sender.getWorld(),
          Vec3.fromLocation(sender.getLocation())
        );
        sender.sendMessage(this.plugin.config().messages().boardCreated(name));
      }));

    this.mgr.command(chess.literal("set_checkerboard")
      .argument(this.boardArgument("board"))
      .flag(this.mgr.flagBuilder("black").withArgument(MaterialArgument.of("black")))
      .flag(this.mgr.flagBuilder("white").withArgument(MaterialArgument.of("white")))
      .flag(this.mgr.flagBuilder("border").withArgument(MaterialArgument.of("border")))
      .handler(ctx -> {
        final ChessBoard board = ctx.get("board");
        final FlagContext flags = ctx.flags();
        board.applyCheckerboard(
          flags.<Material>getValue("black").orElse(Material.BLACK_CONCRETE),
          flags.<Material>getValue("white").orElse(Material.WHITE_CONCRETE),
          flags.get("border")
        );
        ctx.getSender().sendRichMessage("<green>Set blocks to world!");
      }));

    this.mgr.command(chess.literal("delete_board")
      .argument(this.boardArgument("board"))
      .handler(ctx -> {
        final String board = ctx.<ChessBoard>get("board").name();
        this.boardManager.deleteBoard(board);
        ctx.getSender().sendMessage(this.plugin.config().messages().boardDeleted(board));
      }));

    this.mgr.command(chess.literal("challenge")
      .literal("cpu")
      .argument(this.boardArgument("board"))
      .argument(EnumArgument.of(PieceColor.class, "color"))
      .senderType(Player.class)
      .handler(ctx -> {
        final ChessBoard board = ctx.get("board");
        if (board.hasGame()) {
          ctx.getSender().sendRichMessage("<red>Board is occupied.");
          return;
        }
        final PieceColor userColor = ctx.get("color");
        final ChessPlayer user = ChessPlayer.player((Player) ctx.getSender());
        board.startGame(
          userColor == PieceColor.WHITE ? user : ChessPlayer.CPU,
          userColor == PieceColor.BLACK ? user : ChessPlayer.CPU
        );
      }));

    this.mgr.command(chess.literal("challenge")
      .literal("player")
      .argument(this.boardArgument("board"))
      .argument(SinglePlayerSelectorArgument.of("player"))
      .argument(EnumArgument.of(PieceColor.class, "color"))
      .senderType(Player.class)
      .handler(ctx -> {
        final ChessBoard board = ctx.get("board");
        if (board.hasGame() || this.boardManager.challenges().asMap().values().stream().anyMatch(c -> c.board() == board)) {
          ctx.getSender().sendRichMessage("<red>Board is occupied.");
          return;
        }
        final PieceColor userColor = ctx.get("color");
        final Player opponent = ctx.<SinglePlayerSelector>get("player").getPlayer();
        if (ctx.getSender().equals(opponent)) {
          ctx.getSender().sendRichMessage("<red>You cannot challenge yourself.");
          return;
        }
        this.boardManager.challenges().put(opponent.getUniqueId(), new PVPChallenge(board, (Player) ctx.getSender(), opponent, userColor));
        opponent.sendRichMessage(
          "<green>You have been challenged to Chess by " + ctx.getSender().getName() + "! They chose to be " + userColor + ". Type /chess accept to accept. Challenge expires in 30 seconds.");
      }));

    this.mgr.command(chess.literal("accept")
      .senderType(Player.class)
      .handler(ctx -> {
        final Player sender = (Player) ctx.getSender();
        final @Nullable PVPChallenge challenge = this.boardManager.challenges().getIfPresent(sender.getUniqueId());
        if (challenge == null) {
          return;
        }
        this.boardManager.challenges().invalidate(sender.getUniqueId());

        final ChessPlayer senderPlayer = ChessPlayer.player(challenge.challenger());
        final ChessPlayer opp = ChessPlayer.player(challenge.player());
        challenge.board().startGame(
          challenge.challengerColor() == PieceColor.WHITE ? senderPlayer : opp,
          challenge.challengerColor() == PieceColor.BLACK ? senderPlayer : opp
        );
      }));

    this.mgr.command(chess.literal("next_promotion")
      .argument(this.boardArgument("board"))
      .argument(this.promotionArgument("type"))
      .senderType(Player.class)
      .handler(ctx -> {
        final ChessBoard board = ctx.get("board");
        if (board.hasGame() && board.game().hasPlayer((Player) ctx.getSender())) {
          board.game().nextPromotion((Player) ctx.getSender(), ctx.get("type"));
        }
      }));

    this.mgr.command(chess.literal("cpu_move")
      .argument(this.boardArgument("board"))
      .handler(ctx -> {
        final ChessBoard board = ctx.get("board");
        if (board.hasGame()) {
          board.game().cpuMove(ctx.getSender());
        }
      }));

    this.mgr.command(chess.literal("reset")
      .argument(this.boardArgument("board"))
      .handler(ctx -> {
        final ChessBoard board = ctx.get("board");
        if (board.hasGame()) {
          board.game().reset();
          return;
        }
      }));

    this.mgr.command(chess.literal("forfeit")
      .argument(this.boardArgument("board"))
      .senderType(Player.class)
      .handler(ctx -> {
        final ChessBoard board = ctx.get("board");
        if (!board.hasGame()) {
          return;
        }
        final ChessGame game = board.game();
        final Player sender = (Player) ctx.getSender();
        if (!game.hasPlayer(sender)) {
          return;
        }
        final PieceColor color = game.color(ChessPlayer.player(sender));
        board.game().forfeit(color);
      }));
  }

  private CommandArgument<CommandSender, PieceType> promotionArgument(final String name) {
    return this.mgr.argumentBuilder(PieceType.class, name)
      .withParser(new EnumArgument.EnumParser<CommandSender, PieceType>(PieceType.class).map((ctx, type) -> {
        if (VALID_PROMOTIONS.contains(type)) {
          return ArgumentParseResult.success(type);
        } else {
          return ArgumentParseResult.failure(new IllegalArgumentException());
        }
      }))
      .withSuggestionsProvider((sender, input) -> VALID_PROMOTIONS.stream().map(Enum::name).toList())
      .build();
  }

  private CommandArgument<CommandSender, ChessBoard> boardArgument(final String name) {
    return this.mgr.argumentBuilder(ChessBoard.class, name)
      .withParser((sender, queue) -> {
        final ChessBoard g = this.boardManager.board(queue.peek());
        if (g != null) {
          queue.poll();
          return ArgumentParseResult.success(g);
        }
        return ArgumentParseResult.failure(
          ComponentRuntimeException.withMessage(this.plugin.config().messages().noSuchBoard(queue.peek())));
      })
      .withSuggestionsProvider((sender, input) -> this.boardManager.boards().stream().map(ChessBoard::name).toList())
      .build();
  }

  private static PaperCommandManager<CommandSender> createCommandManager(final ChessCraft plugin) {
    final PaperCommandManager<CommandSender> mgr;
    try {
      mgr = PaperCommandManager.createNative(plugin, CommandExecutionCoordinator.simpleCoordinator());
    } catch (final Exception e) {
      throw new RuntimeException(e);
    }
    mgr.registerBrigadier();
    final CloudBrigadierManager<CommandSender, ?> brigMgr = Objects.requireNonNull(mgr.brigadierManager());
    brigMgr.setNativeNumberSuggestions(false);
    new MinecraftExceptionHandler<CommandSender>()
      .withDefaultHandlers()
      .apply(mgr, AudienceProvider.nativeAudience());
    final BiConsumer<CommandSender, CommandExecutionException> handler = Objects.requireNonNull(mgr.getExceptionHandler(CommandExecutionException.class));
    mgr.registerExceptionHandler(CommandExecutionException.class, (sender, ex) -> {
      if (ex.getCause() instanceof CommandCompleted completed) {
        final @Nullable Component message = completed.componentMessage();
        if (message != null) {
          sender.sendMessage(message);
        }
        return;
      }
      handler.accept(sender, ex);
    });
    return mgr;
  }
}
