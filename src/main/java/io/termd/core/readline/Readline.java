/*
 * Copyright 2015 Julien Viet
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

package io.termd.core.readline;

import io.termd.core.term.Device;
import io.termd.core.term.TermInfo;
import io.termd.core.tty.TtyConnection;
import io.termd.core.util.Dimension;
import io.termd.core.util.Helper;

import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Make this class thread safe as SSH will access this class with different threds [sic].
 *
 * @author <a href="mailto:julien@julienviet.com">Julien Viet</a>
 */
public class Readline {

  private final Keymap keymap;
  private final Device device;
  private final Map<String, Function> functions = new HashMap<>();
  private final KeyDecoder decoder;
  private TtyConnection conn;
  private Consumer<int[]> prevReadHandler;
  private Consumer<Dimension> prevSizeHandler;
  private Consumer<int[]> defaultReadHandler;
  private Consumer<Dimension> defaultSizeHandler;
  private Dimension size;
  private Interaction interaction;
  private List<int[]> history;

  public Readline(Keymap keymap) {
    this.device = TermInfo.defaultInfo().getDevice("xterm"); // For now use xterm
    this.keymap = keymap;
    this.decoder = new KeyDecoder(keymap);
    this.history = new ArrayList<>();
  }

  public Interaction getInteraction() {
    return interaction;
  }

  public void setInteraction(Interaction interaction) {
    this.interaction = interaction;
  }

  /**
   * @return the current history
   */
  public List<int[]> getHistory() {
    return history;
  }

  /**
   * Set the history
   *
   * @param history the history
   */
  public void setHistory(List<int[]> history) {
    this.history = history;
  }

  /**
   * @return the last known size
   */
  public Dimension size() {
    return size;
  }

  public Readline addFunction(Function function) {
    functions.put(function.name(), function);
    return this;
  }

  public Readline install(TtyConnection conn) {
    this.prevReadHandler = conn.getStdinHandler();
    this.prevSizeHandler = conn.getSizeHandler();
    this.conn = conn;
    this.conn.setStdinHandler(data -> {
      decoder.append(data);
      deliver();
    });
    this.conn.setSizeHandler(dim -> {
      if (interaction != null && size != null) {
        // Not supported for now
        // interaction.resize(size.width(), dim.width());
      }
      size = dim;
      if (defaultSizeHandler != null) {
        defaultSizeHandler.accept(dim);
      }
    });
    return this;
  }

  private void deliver() {
    while (decoder.hasNext()) {
      if (interaction != null){
        if (!interaction.completing) {
          interaction.handle(decoder.next());
        } else {
          return;
        }
      } else {
        if (defaultReadHandler != null) {
          defaultReadHandler.accept(decoder.clear());
        } else {
          return;
        }
      }
    }
  }

  public void uninstall() {
    conn.setStdinHandler(prevReadHandler);
    conn.setSizeHandler(prevSizeHandler);
    conn = null;
  }

  public Consumer<int[]> readHandler() {
    return defaultReadHandler;
  }

  public Readline setReadHandler(Consumer<int[]> readHandler) {
    this.defaultReadHandler = readHandler;
    return this;
  }

  public Consumer<Dimension> sizeHandler() {
    return defaultSizeHandler;
  }

  public Readline setSizeHandler(Consumer<Dimension> sizeHandler) {
    defaultSizeHandler = sizeHandler;
    return this;
  }

  /**
   * Read a line until a request can be processed.
   *
   * @param requestHandler the requestHandler
   */
  public void readline(String prompt, Consumer<String> requestHandler) {
    readline(prompt, requestHandler, null);
  }

  /**
   * Schedule delivery of pending data in the buffer.
   */
  public void schedulePending() {
    if (decoder.hasNext()) {
      conn.schedule(Readline.this::deliver);
    }
  }

  /**
   * Read a line until a request can be processed.
   *
   * @param requestHandler the requestHandler
   */
  public void readline(String prompt, Consumer<String> requestHandler, Consumer<Completion> completionHandler) {
    if (interaction != null) {
      throw new IllegalStateException("Already reading a line");
    }
    interaction = new Interaction(prompt, requestHandler, completionHandler);
    conn.write(prompt);
  }

  public class Interaction {

    private final Consumer<String> requestHandler;
    private final Consumer<Completion> completionHandler;
    private final Map<String, Object> data;
    private final Map<IntBuffer, Runnable> handlers;
    private boolean completing;
    private final LinkedList<int[]> lines = new LinkedList<>();
    private final LineBuffer buffer = new LineBuffer();
    private final ParsedBuffer parsed = new ParsedBuffer();
    private int historyIndex = -1;
    private String prompt;

    public Interaction(
        String prompt,
        Consumer<String> requestHandler,
        Consumer<Completion> completionHandler) {
      this.handlers = new HashMap<>();
      this.data = new HashMap<>();
      this.prompt = prompt;
      this.requestHandler = requestHandler;
      this.completionHandler = completionHandler;
      handlers.put(Keys.CTRL_M.buffer().asReadOnlyBuffer(), this::doEnter);
      handlers.put(Keys.CTRL_I.buffer().asReadOnlyBuffer(), this::doComplete);
    }

    private void doEnter() {
      for (int j : this.buffer) {
        parsed.accept(j);
      }
      this.buffer.setSize(0);
      if (parsed.escaped) {
        parsed.accept((int) '\r'); // Correct status
        prompt = "> ";
        conn.write("\n> ");
      } else {
        int[] l = new int[parsed.buffer.size()];
        for (int index = 0;index < l.length;index++) {
          l[index] = parsed.buffer.get(index);
        }
        parsed.buffer.clear();
        lines.add(l);
        if (parsed.quoting == Quote.WEAK || parsed.quoting == Quote.STRONG) {
          conn.write("\n> ");
          prompt = "> ";
        } else {
          final StringBuilder raw = new StringBuilder();
          ArrayList<Integer> hist = new ArrayList<>();
          for (int index = 0;index < lines.size();index++) {
            int[] a = lines.get(index);
            if (index > 0) {
              raw.append('\n'); // Use \n for processing
              hist.add((int) '\n');
            }
            for (int b : a) {
              raw.appendCodePoint(b);
              hist.add(b);
            }
          }
          lines.clear();
          history.add(0, hist.stream().mapToInt(Integer::intValue).toArray());
          parsed.buffer.clear();
          conn.write("\n");
          interaction = null;
          requestHandler.accept(raw.toString());
        }
      }
    }

    private void doComplete() {
      if (completionHandler != null) {
        Dimension dim = size; // Copy ref
        int index = this.buffer.getCursor();

        //
        while (index > 0 && this.buffer.getAt(index - 1) != ' ') {
          index--;
        }

        // Compute line : need to test full line :-)
        int linePos = this.buffer.getCursor();
        ParsedBuffer line_ = new ParsedBuffer();
        for (int[] l : lines) {
          for (int j : l) {
            line_.accept(j);
          }
          line_.accept('\n');
        }
        for (int i : parsed.buffer) {
          line_.accept(i);
        }
        for (int i : this.buffer) {
          line_.accept(i);
        }
        int[] line = line_.buffer.stream().mapToInt(i -> i).toArray();

        // Compute prefix
        ParsedBuffer a = new ParsedBuffer();
        for (int i = index; i < this.buffer.getCursor();i++) {
          a.accept(this.buffer.getAt(i));
        }

        completing = true;
        LineBuffer copy = new LineBuffer(this.buffer);
        final AtomicReference<CompletionStatus> status = new AtomicReference<>(CompletionStatus.PENDING);
        completionHandler.accept(new Completion() {

          @Override
          public int[] line() {
            return line;
          }

          @Override
          public int[] prefix() {
            return a.buffer.stream().mapToInt(i -> i).toArray();
          }

          @Override
          public Dimension size() {
            return dim;
          }

          @Override
          public void end() {
            while (true) {
              CompletionStatus current = status.get();
              if (current != CompletionStatus.COMPLETED) {
                if (status.compareAndSet(current, CompletionStatus.COMPLETED)) {
                  switch (current) {
                    case COMPLETING:
                      // Redraw last line with correct prompt
                      if (lines.size() == 0 && parsed.buffer.size() == 0) {
                        conn.write(prompt);
                      } else {
                        conn.write("> ");
                      }
                      conn.stdoutHandler().accept(new LineBuffer().compute(Interaction.this.buffer));
                      break;
                  }
                  // Update status
                  completing = false;
                  // Schedule a delivery of pending data
                  schedulePending();
                  break;
                }
                // Try again
              } else {
                throw new IllegalStateException();
              }
            }
          }

          @Override
          public Completion complete(int[] text, boolean terminal) {
            if (status.compareAndSet(CompletionStatus.PENDING, CompletionStatus.INLINING)) {
              if (text.length > 0 || terminal) {
                for (int z : text) {
                  if (z < 32) {
                    // Todo support \n with $'\n'
                    throw new UnsupportedOperationException("todo");
                  }
                  switch (a.quoting) {
                    case WEAK:
                      switch (z) {
                        case '\\':
                        case '"':
                          if (!a.escaped) {
                            Interaction.this.buffer.insert('\\');
                            a.accept('\\');
                          }
                          Interaction.this.buffer.insert(z);
                          a.accept(z);
                          break;
                        default:
                          if (a.escaped) {
                            // Should beep
                          } else {
                            Interaction.this.buffer.insert(z);
                            a.accept(z);
                          }
                          break;
                      }
                      break;
                    case STRONG:
                      switch (z) {
                        case '\'':
                          Interaction.this.buffer.insert('\'', '\\', z, '\'');
                          a.accept('\'');
                          a.accept('\\');
                          a.accept(z);
                          a.accept('\'');
                          break;
                        default:
                          Interaction.this.buffer.insert(z);
                          a.accept(z);
                          break;
                      }
                      break;
                    case NONE:
                      if (a.escaped) {
                        Interaction.this.buffer.insert(z);
                        a.accept(z);
                      } else {
                        switch (z) {
                          case ' ':
                          case '"':
                          case '\'':
                          case '\\':
                            Interaction.this.buffer.insert('\\', z);
                            a.accept('\\');
                            a.accept(z);
                            break;
                          default:
                            Interaction.this.buffer.insert(z);
                            a.accept(z);
                            break;
                        }
                      }
                      break;
                    default:
                      throw new UnsupportedOperationException("Todo " + a.quoting);
                  }
                }
                if (terminal) {
                  switch (a.quoting) {
                    case WEAK:
                      if (a.escaped) {
                        // Do nothing emit bell
                      } else {
                        Interaction.this.buffer.insert('"', ' ');
                        a.accept('"');
                        a.accept(' ');
                      }
                      break;
                    case STRONG:
                      Interaction.this.buffer.insert('\'', ' ');
                      a.accept('\'');
                      a.accept(' ');
                      break;
                    case NONE:
                      if (a.escaped) {
                        // Do nothing emit bell
                      } else {
                        Interaction.this.buffer.insert(' ');
                        a.accept(' ');
                      }
                      break;
                  }
                }
                conn.stdoutHandler().accept(copy.compute(Interaction.this.buffer));
              }
            } else {
              throw new IllegalStateException();
            }
            return this;
          }

          @Override
          public Completion suggest(int[] text) {
            while (true) {
              CompletionStatus current = status.get();
              if ((current == CompletionStatus.PENDING || current == CompletionStatus.COMPLETING)) {
                if (status.compareAndSet(current, CompletionStatus.COMPLETING)) {
                  if (current == CompletionStatus.PENDING) {
                    conn.write("\n");
                  }
                  conn.stdoutHandler().accept(text);
                  return this;
                }
                // Try again
              } else {
                throw new IllegalStateException();
              }
            }
          }
        });
      }
    }

    private void update(LineBuffer copy, int width) {
      LineBuffer copy3 = new LineBuffer();
      copy3.insert(Helper.toCodePoints(prompt));
      copy3.insert(copy.toArray());
      copy3.setCursor(prompt.length() + copy.getCursor());
      LineBuffer copy2 = new LineBuffer();
      copy2.insert(Helper.toCodePoints(prompt));
      copy2.insert(buffer.toArray());
      copy2.setCursor(prompt.length() + buffer.getCursor());
      copy3.update(copy2, conn.stdoutHandler(), width);
    }

    private void handle(Event event) {
      int width = conn.size().width();
      LineBuffer copy = new LineBuffer(buffer);
      if (event instanceof KeyEvent) {
        KeyEvent key = (KeyEvent) event;
        Runnable handler = handlers.get(key.buffer());
        if (handler != null) {
          handler.run();
        } else {
          for (int i = 0;i < key.length();i++) {
            int codePoint = key.getAt(i);
            buffer.insert(codePoint);
          }
          update(copy, width);
        }
      } else {
        FunctionEvent fname = (FunctionEvent) event;
        Function function = functions.get(fname.name());
        if (function != null) {
          function.apply(this);
        } else {
          System.out.println("Unimplemented function " + fname.name());
        }
        update(copy, width);
      }
    }

    void resize(int oldWith, int newWidth) {

      // Erase screen
      LineBuffer abc = new LineBuffer();
      abc.insert(prompt);
      abc.insert(buffer.toArray());
      abc.setCursor(prompt.length() + buffer.getCursor());

      // Recompute new cursor
      Dimension pos = abc.getCursorPosition(newWidth);
      int curWidth = pos.width();
      int curHeight = pos.height();

      // Recompute new end
      Dimension end = abc.getPosition(abc.getSize(), oldWith);
      int endHeight = end.height() + end.width() / newWidth;

      // Position at the bottom / right
      Consumer<int[]> out = conn.stdoutHandler();
      out.accept(new int[]{'\r'});
      while (curHeight != endHeight) {
        if (curHeight > endHeight) {
          out.accept(new int[]{'\033','[','1','A'});
          curHeight--;
        } else {
          out.accept(new int[]{'\n'});
          curHeight++;
        }
      }

      // Now erase and redraw
      while (curHeight > 0) {
        out.accept(new int[]{'\033','[','1','K'});
        out.accept(new int[]{'\033','[','1','A'});
        curHeight--;
      }
      out.accept(new int[]{'\033','[','1','K'});

      // Now redraw
      out.accept(Helper.toCodePoints(prompt));
      update(new LineBuffer(), newWidth);
    }

    public Map<String, Object> data() {
      return data;
    }

    public List<int[]> history() {
      return history;
    }

    public int getHistoryIndex() {
      return historyIndex;
    }

    public void setHistoryIndex(int historyIndex) {
      this.historyIndex = historyIndex;
    }

    public LineBuffer buffer() {
      return buffer;
    }
  }
}
