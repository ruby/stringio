\Class \StringIO supports accessing a string as a stream,
similar in some ways to [class IO][class io].

You can create a \StingIO instance using:

- StringIO.new(string): returns a new \StringIO object containing the given string.
- StringIO.open(string): passes a new \StringIO object to the given block.

Like an \IO stream, a \StringIO stream has certain properties:

- **Read/write mode**: whether the stream may be read, written, appended to, etc.;
  see [Read/Write Mode][read/write mode].
- **Data mode**: text-only or binary;
  see [Data Mode][data mode].
- **Encodings**: internal and external encodings;
  see [Encodings][encodings].
- **Position**: where in the stream the next read or write is to occur;
  see [Position][position].
- **Line number**: a special, line-oriented, "position" (different from the position mentioned above);
  see [Line Number][line number].
- **Open/closed**: whether the stream is open or closed, for reading or writing.
  see [Open/Closed Streams][open/closed streams].
- **End-of-stream**: whether the position is at the end of the stream;
  see [End-of-Stream][end-of-stream].

## About the Examples

Examples on this page assume that \StringIO has been required:

```
require 'stringio'
```

And that this constant has been defined:

```
TEXT = <<EOT
First line
Second line

Fourth line
Fifth line
EOT
```

## Stream Properties

### Read/Write Mode

#### Summary

|            Mode            | Truncate? |   Read   | Read Pos |  Write   | Write Pos |
|:--------------------------:|:---------:|:--------:|:--------:|:--------:|:---------:|
|  <tt>'r'</tt>: read-only   |    No     | Anywhere |    0     |  Error   |     -     |
|  <tt>'w'</tt>: write-only  |    Yes    |  Error   |    -     | Anywhere |     0     |
| <tt>'a'</tt>: append-only  |    No     |  Error   |    -     | End only |    End    |
| <tt>'r+'</tt>: read/write  |    No     | Anywhere |    0     | Anywhere |     0     |
| <tt>'w+'</tt>: read-write  |    Yes    | Anywhere |    0     | Anywhere |     0     |
| <tt>'a+'</tt>: read/append |    No     | Anywhere |    0     | End only |    End    |

#### `'r'`: Read-Only

Initial state:

```ruby
strio = StringIO.new('foobarbaz', 'r')
strio.pos    # => 0            # Beginning-of-stream.
strio.string # => "foobarbaz"  # Not truncated.
```

May be read anywhere:

```ruby
strio.gets(3) # => "foo"
strio.gets(3) # => "bar"
strio.pos = 9
strio.gets(3) # => nil
```

May not be written:

```ruby
strio.write('foo')  # Raises IOError: not opened for writing
```

#### `'w'`: Write-Only

Initial state:

```ruby
strio = StringIO.new('foo', 'w')
strio.pos    # => 0   # Beginning of stream.
strio.string # => ""  # Initially truncated.
```

May be written anywhere (even past end-of-stream):

```ruby
strio.write('foobar')
strio.string # => "foobar"
strio.rewind
strio.write('FOO')
strio.string # => "FOObar"
strio.pos = 3
strio.write('BAR')
strio.string # => "FOOBAR"
strio.pos = 9
strio.write('baz')
strio.string # => "FOOBAR\u0000\u0000\u0000baz"  # Null-padded.
```

May not be read:

```ruby
strio.read  # Raises IOError: not opened for reading
```

#### `'a'`: Append-Only

Initial state:

```ruby
strio = StringIO.new('foo', 'a')
strio.pos    # => 0      # Beginning-of-stream.
strio.string # => "foo"  # Not truncated.
```

May be written only at the end; position does not affect writing:

```ruby
strio.write('bar')
strio.string # => "foobar"
strio.write('baz')
strio.string # => "foobarbaz"
strio.pos = 400
strio.write('bat')
strio.string # => "foobarbazbat"
```

May not be read:

```ruby
strio.gets  # Raises IOError: not opened for reading
```

#### `'r+'`: Read/Write

Initial state:

```ruby
strio = StringIO.new('foobar', 'r+')
strio.pos    # => 0         # Beginning-of-stream.
strio.string # => "foobar"  # Not truncated.
```

May be written anywhere (even past end-of-stream):

```ruby
strio.write('FOO')
strio.string # => "FOObar"
strio.write('BAR')
strio.string # => "FOOBAR"
strio.write('BAZ')
strio.string # => "FOOBARBAZ"
strio.pos = 12
strio.write('BAT')
strio.string # => "FOOBARBAZ\u0000\u0000\u0000BAT"  # Null padded.
```

May be read anywhere:

```ruby
strio.pos = 0
strio.gets(3) # => "FOO"
strio.pos = 6
strio.gets(3) # => "BAZ"
strio.pos = 400
strio.gets(3) # => nil
```

#### `'w+'`: Read/Write (Initial Truncate)

Initial state:

```ruby
strio = StringIO.new('foo', 'w+')
strio.pos    # => 0   # Beginning-of-stream.
strio.string # => ""  # Truncated.
```

May be written anywhere (even past end-of-stream):

```ruby
strio.write('foobar')
strio.string # => "foobar"
strio.rewind
strio.write('FOO')
strio.string # => "FOObar"
strio.write('BAR')
strio.string # => "FOOBAR"
strio.write('BAZ')
strio.string # => "FOOBARBAZ"
strio.pos = 12
strio.write('BAT')
strio.string # => "FOOBARBAZ\u0000\u0000\u0000BAT"  # Null-padded.
```

May be read anywhere:

```ruby
strio.rewind
strio.gets(3) # => "FOO"
strio.gets(3) # => "BAR"
strio.pos = 12
strio.gets(3) # => "BAT"
strio.pos = 400
strio.gets(3) # => nil
```

#### `'a+'`: Read/Append

Initial state:

```ruby
strio = StringIO.new('foo', 'a+')
strio.pos    # => 0      # Beginning-of-stream.
strio.string # => "foo"  # Not truncated.
```

May be written only at the end; #rewind; position does not affect writing:

```ruby
strio.write('bar')
strio.string # => "foobar"
strio.write('baz')
strio.string # => "foobarbaz"
strio.pos = 400
strio.write('bat')
strio.string # => "foobarbazbat"
```

May be read anywhere:

```ruby
strio.rewind
strio.gets(3) # => "foo"
strio.gets(3) # => "bar"
strio.pos = 9
strio.gets(3) # => "bat"
strio.pos = 400
strio.gets(3) # => nil
```
### Data Mode

To specify whether the stream is to be treated as text or as binary data,
either of the following may be suffixed to any of the string read/write modes above:

- `'t'`: Text;
  sets the default external encoding to Encoding::UTF_8.
- `'b'`: Binary;
  sets the default external encoding to Encoding::ASCII_8BIT.

If neither is given, the stream defaults to text data.

Examples:

```ruby
strio = StringIO.new('foo', 'rt')
strio.external_encoding # => #<Encoding:UTF-8>
data = "\u9990\u9991\u9992\u9993\u9994"
strio = StringIO.new(data, 'rb')
strio.external_encoding # => #<Encoding:BINARY (ASCII-8BIT)>```

When the data mode is specified, the read/write mode may not be omitted:

```ruby
StringIO.new(data, 'b')  # Raises ArgumentError: invalid access mode b
```

A text stream may be changed to binary by calling instance method #binmode;
a binary stream may not be changed to text.

### Encodings

[TODO]

### Position

A stream has a _position_, and integer offset (in bytes) into the stream.
The initial position of a stream is zero.

#### Getting and Setting the Position

Each of these methods gets or sets the position, without otherwise changing the stream:

- #pos: returns the position.
- #pos=: sets the position.
- #rewind: sets the position to zero.
- #seek: sets the position.

Examples:

```ruby
strio = StringIO.new('foobar')
strio.pos # => 0
strio.pos = 3
strio.pos # => 3
strio.rewind
strio.pos # => 0
strio.seek(0, IO::SEEK_END)
strio.pos # => 6
```

#### Position Before and After Reading

Except for #pread, a stream reading method (see [Basic Reading][basic reading])
begins reading at the current position.

Except for #pread, a read method advances the position past the read substring.

Examples:

```ruby
strio = StringIO.new(TEXT)
strio.string # => "First line\nSecond line\n\nFourth line\nFifth line\n"
strio.pos    # => 0
strio.getc   # => "F"
strio.pos    # => 1
strio.gets   # => "irst line\n"
strio.pos    # => 11
strio.pos = 24
strio.gets   # => "Fourth line\n"
strio.pos    # => 36

strio = StringIO.new('тест') # Four 2-byte characters.
strio.pos = 0 # At first byte of first character.
strio.read    # => "тест"
strio.pos = 1 # At second byte of first character.
strio.read    # => "\x82ест"
strio.pos = 2 # At first of second character.
strio.read    # => "ест"

strio = StringIO.new(TEXT)
strio.pos = 15
a = []
strio.each_line {|line| a.push(line) }
a         # => ["nd line\n", "\n", "Fourth line\n", "Fifth line\n"]
strio.pos # => 47  ## End-of-stream.
```

#### Position Before and After Writing

Each of these methods begins writing at the current position,
and advances the position to the end of the written substring:

- #putc(character): writes a given character.
- #write: writes the given objects as strings.
- [Kernel#puts][kernel#puts] writes given objects as strings, each followed by newline.

Examples:

```ruby
strio = StringIO.new('foo')
strio.pos    # => 0
strio.putc('b')
strio.string # => "boo"
strio.pos    # => 1
strio.write('r')
strio.string # => "bro"
strio.pos    # => 2
strio.puts('ew')
strio.string # => "brew\n"
strio.pos    # => 5
strio.pos = 8
strio.write('foo')
strio.string # => "brew\n\u0000\u0000\u0000foo"
strio.pos    # => 11
```

Each of these methods writes _before_ the current position, and decrements the position
so that the written data is next to be read:

- #ungetbyte(byte): unshifts the given byte.
- #ungetc(character): unshifts the given character.

Examples:

```ruby
strio = StringIO.new('foo')
strio.pos = 2
strio.ungetc('x')
strio.pos    # => 1
strio.string # => "fxo"
strio.ungetc('x')
strio.pos    # => 0
strio.string # => "xxo"
```


This method does not affect the position:

- #truncate(size): truncates the stream's string to the given size.

Examples:

```ruby
strio = StringIO.new('foobar')
strio.pos    # => 0
strio.truncate(3)
strio.string # => "foo"
strio.pos    # => 0
strio.pos = 500
strio.truncate(0)
strio.string # => ""
strio.pos    # => 500
```

### Line Number

[TODO]

### Open/Closed Streams

A new stream is open for either reading or writing, and may be open for both;
see [Read/Write Mode][read/write mode].

Each of these methods initializes the read/write mode for a new or re-initialized stream:

- ::new(string = '', mode = 'r+'): returns a new stream.
- ::open(string = '', mode = 'r+'): passes a new stream to the block.
- #reopen(string = '', mode = 'r+'): re-initializes the stream.

Other relevant methods:

- #close: closes the stream for both reading and writing.
- #close_read: closes the stream for reading.
- #close_write: closes the stream for writing.
- #closed?: returns whether the stream is closed for both reading and writing.
- #closed_read?: returns whether the stream is closed for reading.
- #closed_write?: returns whether the stream is closed for writing.

### End-of-Stream

[TODO]

## Basic Stream \IO

### Basic Reading

You can read from the stream using these instance methods:

- #getbyte: reads and returns the next byte.
- #getc: reads and returns the next character.
- #gets: reads and returns all or part of the next line.
- #read: reads and returns all or part of the remaining data in the stream.
- #readlines: reads the remaining data the stream and returns an array of its lines.
- [Kernel#readline][kernel#readline]: like #gets, but raises an exception if at end-of-stream.

You can iterate over the stream using these instance methods:

- #each_byte: reads each remaining byte, passing it to the block.
- #each_char: reads each remaining character, passing it to the block.
- #each_codepoint: reads each remaining codepoint, passing it to the block.
- #each_line: reads all or part of each remaining line, passing the read string to the block

This instance method is useful in a multi-threaded application:

- #pread: reads and returns all or part of the stream.
 
### Basic Writing

You can write to the stream, advancing the position, using these instance methods:

- #putc(character): writes a given character.
- #write: writes the given objects as strings.
- [Kernel#puts][kernel#puts] writes given objects as strings, each followed by newline.

You can "unshift" to the stream using these instance methods;
each  writes _before_ the current position, and decrements the position
so that the written data is next to be read.

- #ungetbyte(byte): unshifts the given byte.
- #ungetc(character): unshifts the given character.

One more writing method:

- #truncate(size): truncates the stream's string to the given size.

## Line \IO

Reading:

- #gets: reads and returns the next line.
- #each_line: reads each remaining line, passing it to the block
- #readlines: reads the remaining data the stream and returns an array of its lines.
- [Kernel#readline][kernel#readline]: like #gets, but raises an exception if at end-of-stream.

Writing:

- [Kernel#puts][kernel#puts]: writes given objects, each followed by newline.

## Character \IO

Reading:

- #each_char: reads each remaining character, passing it to the block.
- #getc: reads and returns the next character.

Writing:

- #putc: writes the given character.
- #ungetc.: unshifts the given character.

## Byte \IO

Reading:

- #each_byte: reads each remaining byte, passing it to the block.
- #getbyte: reads and returns the next byte.

Writing:

- #ungetbyte: unshifts the given byte.

## Codepoint \IO

Reading:

- #each_codepoint: reads each remaining codepoint, passing it to the block.

[class io]:        https://docs.ruby-lang.org/en/master/IO.html
[kernel#puts]:     https://docs.ruby-lang.org/en/master/Kernel.html#method-i-puts
[kernel#readline]: https://docs.ruby-lang.org/en/master/Kernel.html#method-i-readline

[basic reading]:       rdoc-ref:StringIO@Basic+Reading
[basic writing]:       rdoc-ref:StringIO@Basic+Writing
[data mode]:           rdoc-ref:StringIO@Data+Mode
[encodings]:           rdoc-ref:StringIO@Encodings
[end-of-stream]:       rdoc-ref:StringIO@End-of-Stream
[line number]:         rdoc-ref:StringIO@Line+Number
[open/closed streams]: rdoc-ref:StringIO@Open-2FClosed+Streams
[position]:            rdoc-ref:StringIO@Position
[read/write mode]:     rdoc-ref:StringIO@Read-2FWrite+Mode

<!--

TODO:
- Add File constants (e.g., File::RDONLY) to Data Mode section.

-->
