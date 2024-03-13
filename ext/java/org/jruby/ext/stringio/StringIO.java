/***** BEGIN LICENSE BLOCK *****
 * Version: EPL 2.0/GPL 2.0/LGPL 2.1
 *
 * The contents of this file are subject to the Eclipse Public
 * License Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of
 * the License at http://www.eclipse.org/legal/epl-v20.html
 *
 * Software distributed under the License is distributed on an "AS
 * IS" basis, WITHOUT WARRANTY OF ANY KIND, either express or
 * implied. See the License for the specific language governing
 * rights and limitations under the License.
 *
 * Copyright (C) 2006 Ola Bini <ola@ologix.com>
 * Copyright (C) 2006 Ryan Bell <ryan.l.bell@gmail.com>
 * Copyright (C) 2007 Thomas E Enebo <enebo@acm.org>
 * Copyright (C) 2008 Vladimir Sizikov <vsizikov@gmail.com>
 *
 * Alternatively, the contents of this file may be used under the terms of
 * either of the GNU General Public License Version 2 or later (the "GPL"),
 * or the GNU Lesser General Public License Version 2.1 or later (the "LGPL"),
 * in which case the provisions of the GPL or the LGPL are applicable instead
 * of those above. If you wish to allow use of your version of this file only
 * under the terms of either the GPL or the LGPL, and not to allow others to
 * use your version of this file under the terms of the EPL, indicate your
 * decision by deleting the provisions above and replace them with the notice
 * and other provisions required by the GPL or the LGPL. If you do not delete
 * the provisions above, a recipient may use your version of this file under
 * the terms of any one of the EPL, the GPL or the LGPL.
 ***** END LICENSE BLOCK *****/

package org.jruby.ext.stringio;

import org.jcodings.Encoding;
import org.jcodings.specific.ASCIIEncoding;
import org.jruby.*;
import org.jruby.anno.FrameField;
import org.jruby.anno.JRubyClass;
import org.jruby.anno.JRubyMethod;
import org.jruby.ast.util.ArgsUtil;
import org.jruby.java.addons.IOJavaAddons;
import org.jruby.runtime.Arity;
import org.jruby.runtime.Block;
import org.jruby.runtime.Helpers;
import org.jruby.runtime.ThreadContext;
import org.jruby.runtime.builtin.IRubyObject;
import org.jruby.runtime.encoding.EncodingCapable;
import org.jruby.runtime.marshal.DataType;
import org.jruby.util.ArraySupport;
import org.jruby.util.ByteList;
import org.jruby.util.StringSupport;
import org.jruby.util.TypeConverter;
import org.jruby.util.io.EncodingUtils;
import org.jruby.util.io.Getline;
import org.jruby.util.io.ModeFlags;
import org.jruby.util.io.OpenFile;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;

import static org.jruby.RubyEnumerator.enumeratorize;
import static org.jruby.runtime.Visibility.PRIVATE;

@JRubyClass(name="StringIO")
@SuppressWarnings("serial")
public class StringIO extends RubyObject implements EncodingCapable, DataType {
    static class StringIOData {
        /**
         * ATTN: the value of internal might be reset to null
         * (during StringIO.open with block), so watch out for that.
         */
        RubyString string;
        Encoding enc;
        int pos;
        int lineno;
        int flags;
        volatile int locked;
    }
    StringIOData ptr;

    private static final String
    STRINGIO_VERSION = "3.1.1";

    private static final int STRIO_READABLE = ObjectFlags.registry.newFlag(StringIO.class);
    private static final int STRIO_WRITABLE = ObjectFlags.registry.newFlag(StringIO.class);
    private static final int STRIO_READWRITE = (STRIO_READABLE | STRIO_WRITABLE);

    private static final AtomicIntegerFieldUpdater<StringIOData> LOCKED_UPDATER = AtomicIntegerFieldUpdater.newUpdater(StringIOData.class, "locked");

    public static RubyClass createStringIOClass(final Ruby runtime) {
        RubyClass stringIOClass = runtime.defineClass(
                "StringIO", runtime.getObject(), StringIO::new);

        RubyString version = RubyString.newString(runtime, STRINGIO_VERSION);
        stringIOClass.defineConstant("VERSION", version);

        stringIOClass.defineAnnotatedMethods(StringIO.class);
        stringIOClass.includeModule(runtime.getEnumerable());

        if (runtime.getObject().isConstantDefined("Java")) {
            stringIOClass.defineAnnotatedMethods(IOJavaAddons.AnyIO.class);
        }

        RubyModule genericReadable = runtime.getIO().defineOrGetModuleUnder("GenericReadable");
        genericReadable.defineAnnotatedMethods(GenericReadable.class);
        stringIOClass.includeModule(genericReadable);

        RubyModule genericWritable = runtime.getIO().defineOrGetModuleUnder("GenericWritable");
        genericWritable.defineAnnotatedMethods(GenericWritable.class);
        stringIOClass.includeModule(genericWritable);

        return stringIOClass;
    }

    // mri: get_enc
    public Encoding getEncoding() {
        StringIOData ptr = this.ptr;
        Encoding enc = ptr.enc;
        return enc != null ? enc : ptr.string.getEncoding();
    }

    public void setEncoding(Encoding enc) {
        ptr.enc = enc;
    }

    @JRubyMethod(name = "new", rest = true, meta = true)
    public static IRubyObject newInstance(ThreadContext context, IRubyObject recv, IRubyObject[] args, Block block) {
        return RubyIO.newInstance(context, recv, args, block);
    }

    @JRubyMethod(meta = true, rest = true)
    public static IRubyObject open(ThreadContext context, IRubyObject recv, IRubyObject[] args, Block block) {
        StringIO strio = (StringIO)((RubyClass)recv).newInstance(context, args, Block.NULL_BLOCK);
        IRubyObject val = strio;

        if (block.isGiven()) {
            try {
                val = block.yield(context, strio);
            } finally {
                strio.ptr.string = null;
                strio.flags &= ~STRIO_READWRITE;
            }
        }
        return val;
    }

    protected StringIO(Ruby runtime, RubyClass klass) {
        super(runtime, klass);
    }

    @JRubyMethod(optional = 2, visibility = PRIVATE)
    public IRubyObject initialize(ThreadContext context, IRubyObject[] args) {
        Arity.checkArgumentCount(context, args, 0, 2);

        if (ptr == null) {
            ptr = new StringIOData();
        }

        // does not dispatch quite right and is not really necessary for us
        //Helpers.invokeSuper(context, this, metaClass, "initialize", IRubyObject.NULL_ARRAY, Block.NULL_BLOCK);
        strioInit(context, args);
        return this;
    }

    // MRI: strio_init
    private void strioInit(ThreadContext context, IRubyObject[] args) {
        Ruby runtime = context.runtime;
        RubyString string;
        IRubyObject mode;

        StringIOData ptr = this.ptr;

        lock(ptr);
        try {
            int argc = args.length;
            Encoding encoding = null;

            IRubyObject options = ArgsUtil.getOptionsArg(runtime, args);
            if (!options.isNil()) {
                argc--;
                IRubyObject encodingOpt = ArgsUtil.extractKeywordArg(context, "encoding", (RubyHash) options);
                if (!encodingOpt.isNil()) {
                    encoding = EncodingUtils.toEncoding(context, encodingOpt);
                }
            }

            switch (argc) {
                case 2:
                    mode = args[1];
                    final boolean trunc;
                    if (mode instanceof RubyFixnum) {
                        int flags = RubyFixnum.fix2int(mode);
                        ptr.flags = ModeFlags.getOpenFileFlagsFor(flags);
                        trunc = (flags & ModeFlags.TRUNC) != 0;
                    } else {
                        String m = args[1].convertToString().toString();
                        ptr.flags = OpenFile.ioModestrFmode(runtime, m);
                        trunc = m.length() > 0 && m.charAt(0) == 'w';
                    }
                    string = args[0].convertToString();
                    if ((ptr.flags & OpenFile.WRITABLE) != 0 && string.isFrozen()) {
                        throw runtime.newErrnoEACCESError("Permission denied");
                    }
                    if (trunc) {
                        string.resize(0);
                    }
                    break;
                case 1:
                    string = args[0].convertToString();
                    ptr.flags = string.isFrozen() ? OpenFile.READABLE : OpenFile.READWRITE;
                    break;
                case 0:
                    string = RubyString.newEmptyString(runtime, runtime.getDefaultExternalEncoding());
                    ptr.flags = OpenFile.READWRITE;
                    break;
                default:
                    throw runtime.newArgumentError(args.length, 2);
            }

            ptr.string = string;
            ptr.enc = encoding;
            ptr.pos = 0;
            ptr.lineno = 0;
            // funky way of shifting readwrite flags into object flags
            flags |= (ptr.flags & OpenFile.READWRITE) * (STRIO_READABLE / OpenFile.READABLE);
        } finally {
            unlock(ptr);
        }
    }

    // MRI: strio_copy
    @JRubyMethod(visibility = PRIVATE)
    public IRubyObject initialize_copy(ThreadContext context, IRubyObject other) {
        StringIO otherIO = (StringIO) TypeConverter.convertToType(other,
                context.runtime.getClass("StringIO"), "to_strio");

        if (this == otherIO) return this;

        ptr = otherIO.ptr;
        flags = flags & ~STRIO_READWRITE | otherIO.flags & STRIO_READWRITE;

        return this;
    }

    @JRubyMethod
    public IRubyObject binmode(ThreadContext context) {
        StringIOData ptr = this.ptr;
        ptr.enc = EncodingUtils.ascii8bitEncoding(context.runtime);
        if (writable()) ptr.string.setEncoding(ptr.enc);

        return this;
    }

    @JRubyMethod(name = "flush")
    public IRubyObject strio_self() {
        return this;
    }

    @JRubyMethod(name = {"fcntl"}, rest = true)
    public IRubyObject strio_unimpl(ThreadContext context, IRubyObject[] args) {
        throw context.runtime.newNotImplementedError("");
    }

    @JRubyMethod(name = {"fsync"})
    public IRubyObject strioZero(ThreadContext context) {
        return RubyFixnum.zero(context.runtime);
    }

    @JRubyMethod(name = {"sync="})
    public IRubyObject strioFirst(IRubyObject arg) {
        checkInitialized();
        return arg;
    }

    @JRubyMethod(name = {"isatty", "tty?"})
    public IRubyObject strioFalse(ThreadContext context) {
        return context.fals;
    }

    @JRubyMethod(name = {"pid", "fileno"})
    public IRubyObject strioNil(ThreadContext context) {
        return context.nil;
    }

    @JRubyMethod
    public IRubyObject close(ThreadContext context) {
        checkInitialized();
        if ( closed() ) return context.nil;

        // NOTE: This is 2.0 behavior to allow dup'ed StringIO to remain open when original is closed
        flags &= ~STRIO_READWRITE;

        return context.nil;
    }

    @JRubyMethod(name = "closed?")
    public IRubyObject closed_p() {
        checkInitialized();
        return getRuntime().newBoolean(closed());
    }

    @JRubyMethod
    public IRubyObject close_read(ThreadContext context) {
        // ~ checkReadable() :
        checkInitialized();
        if ( (ptr.flags & OpenFile.READABLE) == 0 ) {
            throw context.runtime.newIOError("not opened for reading");
        }
        int flags = this.flags;
        if ( ( flags & STRIO_READABLE ) != 0 ) {
            this.flags = flags & ~STRIO_READABLE;
        }
        return context.nil;
    }

    @JRubyMethod(name = "closed_read?")
    public IRubyObject closed_read_p() {
        checkInitialized();
        return getRuntime().newBoolean(!readable());
    }

    @JRubyMethod
    public IRubyObject close_write(ThreadContext context) {
        // ~ checkWritable() :
        checkInitialized();
        if ( (ptr.flags & OpenFile.WRITABLE) == 0 ) {
            throw context.runtime.newIOError("not opened for writing");
        }
        int flags = this.flags;
        if ( ( flags & STRIO_WRITABLE ) != 0 ) {
            this.flags = flags & ~STRIO_WRITABLE;
        }
        return context.nil;
    }

    @JRubyMethod(name = "closed_write?")
    public IRubyObject closed_write_p() {
        checkInitialized();
        return getRuntime().newBoolean(!writable());
    }

    // MRI: strio_each
    @JRubyMethod(name = "each", writes = FrameField.LASTLINE)
    public IRubyObject each(ThreadContext context, Block block) {
        if (!block.isGiven()) return enumeratorize(context.runtime, this, "each");

        return Getline.getlineCall(context, GETLINE_YIELD, this, getEncoding(), 0, null, null, null, block);
    }

    // MRI: strio_each
    @JRubyMethod(name = "each", writes = FrameField.LASTLINE)
    public IRubyObject each(ThreadContext context, IRubyObject arg0, Block block) {
        if (!block.isGiven()) return enumeratorize(context.runtime, this, "each", arg0);

        return Getline.getlineCall(context, GETLINE_YIELD, this, getEncoding(), 1, arg0, null, null, block);
    }

    // MRI: strio_each
    @JRubyMethod(name = "each", writes = FrameField.LASTLINE)
    public IRubyObject each(ThreadContext context, IRubyObject arg0, IRubyObject arg1, Block block) {
        if (!block.isGiven()) return enumeratorize(context.runtime, this, "each", Helpers.arrayOf(arg0, arg1));

        return Getline.getlineCall(context, GETLINE_YIELD, this, getEncoding(), 2, arg0, arg1, null, block);
    }

    // MRI: strio_each
    @JRubyMethod(name = "each")
    public IRubyObject each(ThreadContext context, IRubyObject arg0, IRubyObject arg1, IRubyObject arg2, Block block) {
        if (!block.isGiven()) return enumeratorize(context.runtime, this, "each", Helpers.arrayOf(arg0, arg1, arg2));

        return Getline.getlineCall(context, GETLINE_YIELD, this, getEncoding(), 3, arg0, arg1, arg2, block);
    }

    public IRubyObject each(ThreadContext context, IRubyObject[] args, Block block) {
        if (!block.isGiven()) return enumeratorize(context.runtime, this, "each", args);
        switch (args.length) {
            case 0:
                return each(context, block);
            case 1:
                return each(context, args[0], block);
            case 2:
                return each(context, args[0], args[1], block);
            case 3:
                return each(context, args[0], args[1], args[2], block);
            default:
                Arity.raiseArgumentError(context, args.length, 0, 3);
                throw new AssertionError("BUG");
        }
    }

    @JRubyMethod(name = "each_line")
    public IRubyObject each_line(ThreadContext context, Block block) {
        if (!block.isGiven()) return enumeratorize(context.runtime, this, "each_line");

        return each(context, block);
    }

    @JRubyMethod(name = "each_line")
    public IRubyObject each_line(ThreadContext context, IRubyObject arg0, Block block) {
        if (!block.isGiven()) return enumeratorize(context.runtime, this, "each_line", arg0);

        return each(context, arg0, block);
    }

    @JRubyMethod(name = "each_line")
    public IRubyObject each_line(ThreadContext context, IRubyObject arg0, IRubyObject arg1, Block block) {
        if (!block.isGiven()) return enumeratorize(context.runtime, this, "each_line", arg0, arg1);

        return each(context, arg0, arg1, block);
    }

    @JRubyMethod(name = "each_line")
    public IRubyObject each_line(ThreadContext context, IRubyObject arg0, IRubyObject arg1, IRubyObject arg2, Block block) {
        if (!block.isGiven()) return enumeratorize(context.runtime, this, "each_line", arg0, arg1, arg2);

        return each(context, arg0, arg1, arg2, block);
    }

    public IRubyObject each_line(ThreadContext context, IRubyObject[] args, Block block) {
        if (!block.isGiven()) return enumeratorize(context.runtime, this, "each_line", args);
        switch (args.length) {
            case 0:
                return each_line(context, block);
            case 1:
                return each_line(context, args[0], block);
            case 2:
                return each_line(context, args[0], args[1], block);
            case 3:
                return each_line(context, args[0], args[1], args[2], block);
            default:
                Arity.raiseArgumentError(context, args.length, 0, 3);
                throw new AssertionError("BUG");
        }
    }

    @JRubyMethod(name = {"each_byte"})
    public IRubyObject each_byte(ThreadContext context, Block block) {
        Ruby runtime = context.runtime;

        if (!block.isGiven()) return enumeratorize(runtime, this, "each_byte");

        checkReadable();
        StringIOData ptr = this.ptr;

        lock(ptr);
        try {
            ByteList bytes = ptr.string.getByteList();

            // Check the length every iteration, since
            // the block can modify this string.
            while (ptr.pos < bytes.length()) {
                block.yield(context, runtime.newFixnum(bytes.get(ptr.pos++) & 0xFF));
            }
        } finally {
            unlock(ptr);
        }

        return this;
    }

    @JRubyMethod
    public IRubyObject each_char(final ThreadContext context, final Block block) {
        if (!block.isGiven()) return enumeratorize(context.runtime, this, "each_char");

        IRubyObject c;
        while (!(c = getc(context)).isNil()) {
            block.yieldSpecific(context, c);
        }
        return this;
    }

    @JRubyMethod(name = {"eof", "eof?"})
    public IRubyObject eof(ThreadContext context) {
        checkReadable();
        if (ptr.pos < ptr.string.size()) return context.fals;
        return context.tru;
    }

    private boolean isEndOfString() {
        return ptr.pos >= ptr.string.size();
    }

    @JRubyMethod(name = "getc")
    public IRubyObject getc(ThreadContext context) {
        checkReadable();

        if (isEndOfString()) return context.nil;

        StringIOData ptr = this.ptr;

        lock(ptr);
        try {
            int start = ptr.pos;
            RubyString string = ptr.string;
            int total = 1 + StringSupport.bytesToFixBrokenTrailingCharacter(string.getByteList(), start + 1);

            ptr.pos += total;

            return context.runtime.newString(string.getByteList().makeShared(start, total));
        } finally {
            unlock(ptr);
        }
    }

    @JRubyMethod(name = "getbyte")
    public IRubyObject getbyte(ThreadContext context) {
        checkReadable();

        if (isEndOfString()) return context.nil;

        int c;
        StringIOData ptr = this.ptr;
        lock(ptr);
        try {
            c = ptr.string.getByteList().get(ptr.pos++) & 0xFF;
        } finally {
            unlock(ptr);
        }

        return context.runtime.newFixnum(c);
    }

    // must be called under lock
    private RubyString strioSubstr(Ruby runtime, int pos, int len, Encoding enc) {
        StringIOData ptr = this.ptr;

        final RubyString string = ptr.string;
        final ByteList stringBytes = string.getByteList();
        int rlen = string.size() - pos;

        if (len > rlen) len = rlen;
        if (len < 0) len = 0;

        if (len == 0) return RubyString.newEmptyString(runtime, enc);
        string.setByteListShared(); // we only share the byte[] buffer but its easier this way
        return RubyString.newStringShared(runtime, stringBytes.getUnsafeBytes(), stringBytes.getBegin() + pos, len, enc);
    }

    private static final int CHAR_BIT = 8;

    private static void bm_init_skip(int[] skip, byte[] pat, int patPtr, int m) {
        int c;

        for (c = 0; c < (1 << CHAR_BIT); c++) {
            skip[c] = m;
        }
        while ((--m) > 0) {
            skip[pat[patPtr++]] = m;
        }
    }

    // Note that this is substantially more complex in 2.0 (Onigmo)
    private static int bm_search(byte[] little, int lstart, int llen, byte[] big, int bstart, int blen, int[] skip) {
        int i, j, k;

        i = llen - 1;
        while (i < blen) {
            k = i;
            j = llen - 1;
            while (j >= 0 && big[k + bstart] == little[j + lstart]) {
                k--;
                j--;
            }
            if (j < 0) return k + 1;
            i += skip[big[i + bstart] & 0xFF];
        }
        return -1;
    }

    @JRubyMethod(name = "gets", writes = FrameField.LASTLINE)
    public IRubyObject gets(ThreadContext context) {
        return Getline.getlineCall(context, GETLINE, this, getEncoding());
    }

    @JRubyMethod(name = "gets", writes = FrameField.LASTLINE)
    public IRubyObject gets(ThreadContext context, IRubyObject arg0) {
        return Getline.getlineCall(context, GETLINE, this, getEncoding(), arg0);
    }

    @JRubyMethod(name = "gets", writes = FrameField.LASTLINE)
    public IRubyObject gets(ThreadContext context, IRubyObject arg0, IRubyObject arg1) {
        return Getline.getlineCall(context, GETLINE, this, getEncoding(), arg0, arg1);
    }

    @JRubyMethod(name = "gets", writes = FrameField.LASTLINE)
    public IRubyObject gets(ThreadContext context, IRubyObject arg0, IRubyObject arg1, IRubyObject arg2) {
        return Getline.getlineCall(context, GETLINE, this, getEncoding(), arg0, arg1, arg2);
    }

    public IRubyObject gets(ThreadContext context, IRubyObject[] args) {
        switch (args.length) {
            case 0:
                return gets(context);
            case 1:
                return gets(context, args[0]);
            case 2:
                return gets(context, args[0], args[1]);
            case 3:
                return gets(context, args[0], args[1], args[2]);
            default:
                Arity.raiseArgumentError(context, args.length, 0, 3);
                throw new AssertionError("BUG");
        }
    }

    private static final Getline.Callback<StringIO, IRubyObject> GETLINE = (context, self, rs, limit, chomp, block) -> {
        if (limit == 0) {
            return RubyString.newEmptyString(context.runtime, self.getEncoding());
        }

        if (rs.isNil()) chomp = false;

        IRubyObject result = self.getline(context, rs, limit, chomp);

        context.setLastLine(result);

        return result;
    };

    private static final Getline.Callback<StringIO, StringIO> GETLINE_YIELD = (context, self, rs, limit, chomp, block) -> {
        IRubyObject line;

        if (limit == 0) {
            throw context.runtime.newArgumentError("invalid limit: 0 for each_line");
        }

        if (rs.isNil()) chomp = false;

        while (!(line = self.getline(context, rs, limit, chomp)).isNil()) {
            block.yieldSpecific(context, line);
        }

        return self;
    };

    private static final Getline.Callback<StringIO, RubyArray<IRubyObject>> GETLINE_ARY = (context, self, rs, limit, chomp, block) -> {
        @SuppressWarnings("unchecked")
        RubyArray<IRubyObject> ary = (RubyArray<IRubyObject>) context.runtime.newArray();
        IRubyObject line;

        if (limit == 0) {
            throw context.runtime.newArgumentError("invalid limit: 0 for readlines");
        }

        if (rs.isNil()) chomp = false;

        while (!(line = self.getline(context, rs, limit, chomp)).isNil()) {
            ary.append(line);
        }

        return ary;
    };

    // strio_getline
    private IRubyObject getline(ThreadContext context, final IRubyObject rs, int limit, boolean chomp) {
        Ruby runtime = context.runtime;

        RubyString str;

        checkReadable();

        int n;

        if (isEndOfString()) {
            return context.nil;
        }

        StringIOData ptr = this.ptr;
        Encoding enc = getEncoding();

        lock(ptr);
        try {
            final ByteList string = ptr.string.getByteList();
            final byte[] stringBytes = string.getUnsafeBytes();
            int begin = string.getBegin();
            int pos = ptr.pos;
            int s = begin + pos;
            int e = begin + string.getRealSize();
            int p;
            int w = 0;

            if (limit > 0 && s + limit < e) {
                e = getEncoding().rightAdjustCharHead(stringBytes, s, s + limit, e);
            }
            if (rs == context.nil) {
                if (chomp) {
                    w = chompNewlineWidth(stringBytes, s, e);
                }
                str = strioSubstr(runtime, pos, e - s - w, enc);
            } else if ((n = ((RubyString) rs).size()) == 0) {
                int paragraph_end = 0;
                p = s;
                while (stringBytes[p] == '\n') {
                    if (++p == e) {
                        return context.nil;
                    }
                }
                s = p;
                while ((p = StringSupport.memchr(stringBytes, p, '\n', e - p)) != -1 && (p != e)) {
                    p++;
                    if (!((p < e && stringBytes[p] == '\n') ||
                            (p + 1 < e && stringBytes[p] == '\r' && stringBytes[p+1] == '\n'))) {
                        continue;
                    }
                    paragraph_end = p - ((stringBytes[p-2] == '\r') ? 2 : 1);
                    while ((p < e && stringBytes[p] == '\n') ||
                    (p + 1 < e && stringBytes[p] == '\r' && stringBytes[p+1] == '\n')) {
                        p += (stringBytes[p] == '\r') ? 2 : 1;
                    }
                    e = p;
                    break;
                }
                if (chomp && paragraph_end != 0) {
                    w = e - paragraph_end;
                }
                str = strioSubstr(runtime, s - begin, e - s - w, enc);
            } else if (n == 1) {
                RubyString strStr = (RubyString) rs;
                ByteList strByteList = strStr.getByteList();
                if ((p = StringSupport.memchr(stringBytes, s, strByteList.get(0), e - s)) != -1) {
                    e = p + 1;
                    w = (chomp ? ((p > s && stringBytes[p-1] == '\r')?1:0) + 1 : 0);
                }
                str = strioSubstr(runtime, pos, e - s - w, enc);
            } else {
                if (n < e - s + (chomp ? 1 : 0)) {
                    RubyString rsStr = (RubyString) rs;
                    ByteList rsByteList = rsStr.getByteList();
                    byte[] rsBytes = rsByteList.getUnsafeBytes();

                    /* unless chomping, RS at the end does not matter */
                    if (e - s < 1024 || n == e - s) {
                        for (p = s; p + n <= e; ++p) {
                            if (ByteList.memcmp(stringBytes, p, rsBytes, 0, n) == 0) {
                                e = p + n;
                                w = (chomp ? n : 0);
                                break;
                            }
                        }
                    } else {
                        int[] skip = new int[1 << CHAR_BIT];
                        int pos2;
                        p = rsByteList.getBegin();
                        bm_init_skip(skip, rsBytes, p, n);
                        if ((pos2 = bm_search(rsBytes, p, n, stringBytes, s, e - s, skip)) >= 0) {
                            e = s + pos2 + n;
                        }
                    }
                }
                str = strioSubstr(runtime, pos, e - s - w, enc);
            }
            ptr.pos = e - begin;
            ptr.lineno++;
        } finally {
            unlock(ptr);
        }

        return str;
    }

    private static int chompNewlineWidth(byte[] bytes, int s, int e) {
        if (e > s && bytes[--e] == '\n') {
            if (e > s && bytes[--e] == '\r') return 2;
            return 1;
        }
        return 0;
    }

    @JRubyMethod(name = {"length", "size"})
    public IRubyObject length() {
        checkInitialized();
        checkFinalized();
        return getRuntime().newFixnum(ptr.string.size());
    }

    @JRubyMethod(name = "lineno")
    public IRubyObject lineno(ThreadContext context) {
        return context.runtime.newFixnum(ptr.lineno);
    }

    @JRubyMethod(name = "lineno=", required = 1)
    public IRubyObject set_lineno(ThreadContext context, IRubyObject arg) {
        ptr.lineno = RubyNumeric.fix2int(arg);

        return context.nil;
    }

    @JRubyMethod(name = {"pos", "tell"})
    public IRubyObject pos(ThreadContext context) {
        checkInitialized();

        return context.runtime.newFixnum(ptr.pos);
    }

    @JRubyMethod(name = "pos=", required = 1)
    public IRubyObject set_pos(IRubyObject arg) {
        checkInitialized();

        long p = RubyNumeric.fix2long(arg);

        if (p < 0) throw getRuntime().newErrnoEINVALError(arg.toString());

        if (p > Integer.MAX_VALUE) throw getRuntime().newArgumentError("JRuby does not support StringIO larger than " + Integer.MAX_VALUE + " bytes");

        ptr.pos = (int)p;

        return arg;
    }

    private void strioExtend(int pos, int len) {
        StringIOData ptr = this.ptr;

        lock(ptr);
        try {
            RubyString string = ptr.string;
            final int olen = string.size();
            if (pos + len > olen) {
                string.resize(pos + len);
                if (pos > olen) {
                    string.modify19();
                    ByteList ptrByteList = string.getByteList();
                    // zero the gap
                    int begin = ptrByteList.getBegin();
                    Arrays.fill(ptrByteList.getUnsafeBytes(),
                            begin + olen,
                            begin + pos,
                            (byte) 0);
                }
            } else {
                string.modify19();
            }
        } finally {
            unlock(ptr);
        }
    }

    // MRI: strio_putc
    @JRubyMethod(name = "putc")
    public IRubyObject putc(ThreadContext context, IRubyObject ch) {
        Ruby runtime = context.runtime;
        checkWritable();
        IRubyObject str;

        checkModifiable();
        if (ch instanceof RubyString) {
            str = ((RubyString)ch).substr19(runtime, 0, 1);
        }
        else {
            byte c = RubyNumeric.num2chr(ch);
            str = RubyString.newString(runtime, new byte[]{c});
        }
        write(context, str);
        return ch;
    }

    public static final ByteList NEWLINE = ByteList.create("\n");

    @JRubyMethod(name = "read", optional = 2)
    @SuppressWarnings("fallthrough")
    public IRubyObject read(ThreadContext context, IRubyObject[] args) {
        checkReadable();

        final Ruby runtime = context.runtime;
        IRubyObject str = context.nil;
        int len;
        boolean binary = false;

        StringIOData ptr = this.ptr;
        int pos = ptr.pos;
        final RubyString string;

        lock(ptr);
        try {
            switch (args.length) {
                case 2:
                    str = args[1];
                    if (!str.isNil()) {
                        str = str.convertToString();
                        ((RubyString) str).modify();
                    }
                case 1:
                    if (!args[0].isNil()) {
                        len = RubyNumeric.fix2int(args[0]);

                        if (len < 0) {
                            throw runtime.newArgumentError("negative length " + len + " given");
                        }
                        if (len > 0 && isEndOfString()) {
                            if (!str.isNil()) ((RubyString) str).resize(0);
                            return context.nil;
                        }
                        binary = true;
                        break;
                    }
                case 0:
                    len = ptr.string.size();
                    if (len <= pos) {
                        Encoding enc = binary ? ASCIIEncoding.INSTANCE : getEncoding();
                        if (str.isNil()) {
                            str = runtime.newString();
                        } else {
                            ((RubyString) str).resize(0);
                        }
                        ((RubyString) str).setEncoding(enc);
                        return str;
                    } else {
                        len -= pos;
                    }
                    break;
                default:
                    throw runtime.newArgumentError(args.length, 0, 2);
            }

            if (str.isNil()) {
                Encoding enc = binary ? ASCIIEncoding.INSTANCE : getEncoding();
                string = strioSubstr(runtime, pos, len, enc);
            } else {
                string = (RubyString) str;
                RubyString myString = ptr.string;
                int rest = myString.size() - pos;
                if (len > rest) len = rest;
                string.resize(len);
                ByteList strByteList = string.getByteList();
                byte[] strBytes = strByteList.getUnsafeBytes();
                ByteList dataByteList = myString.getByteList();
                byte[] dataBytes = dataByteList.getUnsafeBytes();
                System.arraycopy(dataBytes, dataByteList.getBegin() + pos, strBytes, strByteList.getBegin(), len);
                if (binary) {
                    string.setEncoding(ASCIIEncoding.INSTANCE);
                } else {
                    string.setEncoding(myString.getEncoding());
                }
            }
            ptr.pos += string.size();
        } finally {
            unlock(ptr);
        }

        return string;
    }

    @JRubyMethod(name = "pread", required = 2, optional = 1)
    @SuppressWarnings("fallthrough")
    public IRubyObject pread(ThreadContext context, IRubyObject[] args) {
        checkReadable();

        final Ruby runtime = context.runtime;
        IRubyObject str = context.nil;
        int len;
        int offset;

        StringIOData ptr = this.ptr;
        final RubyString string;

        switch (args.length) {
            case 3:
                str = args[2];
                if (!str.isNil()) {
                    str = str.convertToString();
                    ((RubyString) str).modify();
                }
            case 2:
                len = RubyNumeric.fix2int(args[0]);
                offset = RubyNumeric.fix2int(args[1]);
                if (!args[0].isNil()) {
                    len = RubyNumeric.fix2int(args[0]);

                    if (len < 0) {
                        throw runtime.newArgumentError("negative length " + len + " given");
                    }

                    if (offset < 0) {
                        throw runtime.newErrnoEINVALError("pread: Invalid offset argument");
                    }
                }
                break;
            default:
                throw runtime.newArgumentError(args.length, 0, 2);
        }

        lock(ptr);
        try {
            RubyString myString = ptr.string;
            if (offset >= myString.size()) {
                throw context.runtime.newEOFError();
            }

            if (str.isNil()) {
                return strioSubstr(runtime, offset, len, ASCIIEncoding.INSTANCE);
            }

            string = (RubyString) str;
            int rest = myString.size() - offset;
            if (len > rest) len = rest;
            string.resize(len);
            ByteList strByteList = string.getByteList();
            byte[] strBytes = strByteList.getUnsafeBytes();
            ByteList dataByteList = myString.getByteList();
            byte[] dataBytes = dataByteList.getUnsafeBytes();
            System.arraycopy(dataBytes, dataByteList.getBegin() + offset, strBytes, strByteList.getBegin(), len);
            string.setEncoding(ASCIIEncoding.INSTANCE);
        } finally {
            unlock(ptr);
        }

        return string;
    }

    @JRubyMethod(name = "readlines")
    public IRubyObject readlines(ThreadContext context) {
        return Getline.getlineCall(context, GETLINE_ARY, this, getEncoding());
    }

    @JRubyMethod(name = "readlines")
    public IRubyObject readlines(ThreadContext context, IRubyObject arg0) {
        return Getline.getlineCall(context, GETLINE_ARY, this, getEncoding(), arg0);
    }

    @JRubyMethod(name = "readlines")
    public IRubyObject readlines(ThreadContext context, IRubyObject arg0, IRubyObject arg1) {
        return Getline.getlineCall(context, GETLINE_ARY, this, getEncoding(), arg0, arg1);
    }

    @JRubyMethod(name = "readlines")
    public IRubyObject readlines(ThreadContext context, IRubyObject arg0, IRubyObject arg1, IRubyObject arg2) {
        return Getline.getlineCall(context, GETLINE_ARY, this, getEncoding(), arg0, arg1, arg2);
    }

    public IRubyObject readlines(ThreadContext context, IRubyObject[] args) {
        switch (args.length) {
            case 0:
                return readlines(context);
            case 1:
                return readlines(context, args[0]);
            case 2:
                return readlines(context, args[0], args[1]);
            case 3:
                return readlines(context, args[0], args[1], args[2]);
            default:
                Arity.raiseArgumentError(context, args.length, 0, 3);
                throw new AssertionError("BUG");
        }
    }

    // MRI: strio_reopen
    @JRubyMethod(name = "reopen", optional = 2)
    public IRubyObject reopen(ThreadContext context, IRubyObject[] args) {
        int argc = Arity.checkArgumentCount(context, args, 0, 2);

        checkFrozen();

        if (argc == 1 && !(args[0] instanceof RubyString)) {
            return initialize_copy(context, args[0]);
        }

        // reset the state
        strioInit(context, args);
        return this;
    }

    @JRubyMethod(name = "rewind")
    public IRubyObject rewind(ThreadContext context) {
        checkInitialized();

        StringIOData ptr = this.ptr;

        lock(ptr);
        try {
            ptr.pos = 0;
            ptr.lineno = 0;
        } finally {
            unlock(ptr);
        }

        return RubyFixnum.zero(context.runtime);
    }

    @JRubyMethod(required = 1, optional = 1)
    public IRubyObject seek(ThreadContext context, IRubyObject[] args) {
        int argc = Arity.checkArgumentCount(context, args, 1, 2);

        Ruby runtime = context.runtime;

        checkFrozen();
        checkFinalized();

        int offset = RubyNumeric.num2int(args[0]);
        IRubyObject whence = context.nil;

        if (argc > 1 && !args[0].isNil()) whence = args[1];

        checkOpen();

        StringIOData ptr = this.ptr;

        lock(ptr);
        try {
            switch (whence.isNil() ? 0 : RubyNumeric.num2int(whence)) {
                case 0:
                    break;
                case 1:
                    offset += ptr.pos;
                    break;
                case 2:
                    offset += ptr.string.size();
                    break;
                default:
                    throw runtime.newErrnoEINVALError("invalid whence");
            }

            if (offset < 0) throw runtime.newErrnoEINVALError("invalid seek value");

            ptr.pos = offset;
        } finally {
            unlock(ptr);
        }

        return RubyFixnum.zero(runtime);
    }

    @JRubyMethod(name = "string=", required = 1)
    public IRubyObject set_string(IRubyObject arg) {
        checkFrozen();
        StringIOData ptr = this.ptr;

        lock(ptr);
        try {
            ptr.flags &= ~OpenFile.READWRITE;
            RubyString str = arg.convertToString();
            ptr.flags = str.isFrozen() ? OpenFile.READABLE : OpenFile.READWRITE;
            ptr.pos = 0;
            ptr.lineno = 0;
            return ptr.string = str;
        } finally {
            unlock(ptr);
        }
    }

    @JRubyMethod(name = "string")
    public IRubyObject string(ThreadContext context) {
        RubyString string = ptr.string;
        if (string == null) return context.nil;

        return string;
    }

    @JRubyMethod(name = "sync")
    public IRubyObject sync(ThreadContext context) {
        checkInitialized();
        return context.tru;
    }

    // only here for the fake-out class in org.jruby
    public IRubyObject sysread(IRubyObject[] args) {
        return GenericReadable.sysread(getRuntime().getCurrentContext(), this, args);
    }

    @JRubyMethod(name = "truncate", required = 1)
    public IRubyObject truncate(IRubyObject len) {
        checkWritable();

        int l = RubyFixnum.fix2int(len);
        StringIOData ptr = this.ptr;
        RubyString string = ptr.string;

        lock(ptr);
        try {
            int plen = string.size();
            if (l < 0) {
                throw getRuntime().newErrnoEINVALError("negative legnth");
            }
            string.resize(l);
            ByteList buf = string.getByteList();
            if (plen < l) {
                // zero the gap
                Arrays.fill(buf.getUnsafeBytes(), buf.getBegin() + plen, buf.getBegin() + l, (byte) 0);
            }
        } finally {
            unlock(ptr);
        }

        return len;
    }

    @JRubyMethod(name = "ungetc")
    public IRubyObject ungetc(ThreadContext context, IRubyObject arg) {
        Encoding enc, enc2;

        checkModifiable();
        checkReadable();

        if (arg.isNil()) return arg;
        if (arg instanceof RubyInteger) {
            int len, cc = RubyNumeric.num2int(arg);
            byte[] buf = new byte[16];

            enc = getEncoding();
            len = enc.codeToMbcLength(cc);
            if (len <= 0) EncodingUtils.encUintChr(context, cc, enc);
            enc.codeToMbc(cc, buf, 0);
            ungetbyteCommon(buf, 0, len);
            return context.nil;
        } else {
            arg = arg.convertToString();
            enc = getEncoding();
            RubyString argStr = (RubyString) arg;
            enc2 = argStr.getEncoding();
            if (enc != enc2 && enc != ASCIIEncoding.INSTANCE) {
                argStr = EncodingUtils.strConvEnc(context, argStr, enc2, enc);
            }
            ByteList argBytes = argStr.getByteList();
            ungetbyteCommon(argBytes.unsafeBytes(), argBytes.begin(), argBytes.realSize());
            return context.nil;
        }
    }

    private void ungetbyteCommon(int c) {
        StringIOData ptr = this.ptr;

        lock(ptr);
        try {
            RubyString string = ptr.string;
            string.modify();
            ptr.pos--;

            ByteList bytes = string.getByteList();

            if (isEndOfString()) bytes.length(ptr.pos + 1);

            if (ptr.pos == -1) {
                bytes.prepend((byte) c);
                ptr.pos = 0;
            } else {
                bytes.set(ptr.pos, c);
            }
        } finally {
            unlock(ptr);
        }
    }

    private void ungetbyteCommon(RubyString ungetBytes) {
        ByteList ungetByteList = ungetBytes.getByteList();
        ungetbyteCommon(ungetByteList.unsafeBytes(), ungetByteList.begin(), ungetByteList.realSize());
    }

    private void ungetbyteCommon(byte[] ungetBytes, int ungetBegin, int ungetLen) {
        final int start; // = ptr.pos;

        if (ungetLen == 0) return;

        StringIOData ptr = this.ptr;

        lock(ptr);
        try {
            RubyString string = ptr.string;
            string.modify();

            int pos = ptr.pos;
            if (ungetLen > pos) {
                start = 0;
            } else {
                start = pos - ungetLen;
            }

            ByteList byteList = string.getByteList();

            if (isEndOfString()) byteList.length(Math.max(pos, ungetLen));

            byteList.replace(start, pos - start, ungetBytes, ungetBegin, ungetLen);

            ptr.pos = start;
        } finally {
            unlock(ptr);
        }
    }

    @JRubyMethod
    public IRubyObject ungetbyte(ThreadContext context, IRubyObject arg) {
        // TODO: Not a line-by-line port.
        checkReadable();

        if (arg.isNil()) return arg;

        checkModifiable();

        if (arg instanceof RubyInteger) {
            ungetbyteCommon(((RubyInteger) ((RubyInteger) arg).op_mod(context, 256)).getIntValue());
        } else {
            ungetbyteCommon(arg.convertToString());
        }

        return context.nil;
    }

    // MRI: strio_write
    @JRubyMethod(name = "write")
    public IRubyObject write(ThreadContext context, IRubyObject arg) {
        Ruby runtime = context.runtime;
        return RubyFixnum.newFixnum(runtime, stringIOWrite(context, runtime, arg));
    }

    @JRubyMethod(name = "write", required = 1, rest = true)
    public IRubyObject write(ThreadContext context, IRubyObject[] args) {
        Arity.checkArgumentCount(context, args, 1, -1);

        Ruby runtime = context.runtime;
        long len = 0;
        for (IRubyObject arg : args) {
            len += stringIOWrite(context, runtime, arg);
        }
        return RubyFixnum.newFixnum(runtime, len);
    }

    private static final MethodHandle CAT_WITH_CODE_RANGE;

    static {
        MethodHandle cat;
        try {
            cat = MethodHandles.publicLookup().findVirtual(RubyString.class, "catWithCodeRange", MethodType.methodType(RubyString.class, RubyString.class));
        } catch (NoSuchMethodException | IllegalAccessException ex) {
            try {
                cat = MethodHandles.publicLookup().findVirtual(RubyString.class, "cat19", MethodType.methodType(RubyString.class, RubyString.class));
            } catch (NoSuchMethodException | IllegalAccessException ex2) {
                throw new ExceptionInInitializerError(ex2);
            }
        }

        CAT_WITH_CODE_RANGE = cat;
    }

    // MRI: strio_write
    private long stringIOWrite(ThreadContext context, Ruby runtime, IRubyObject arg) {
        checkWritable();

        RubyString str = arg.asString();
        int len, olen;

        StringIOData ptr = this.ptr;

        lock(ptr);
        try {
            final Encoding enc = getEncoding();
            final Encoding encStr = str.getEncoding();
            if (enc != encStr && enc != EncodingUtils.ascii8bitEncoding(runtime)
                    // this is a hack because we don't seem to handle incoming ASCII-8BIT properly in transcoder
                    && encStr != ASCIIEncoding.INSTANCE) {
                str = EncodingUtils.strConvEnc(context, str, encStr, enc);
            }
            final ByteList strByteList = str.getByteList();
            len = str.size();
            if (len == 0) return 0;
            checkModifiable();
            RubyString myString = ptr.string;
            olen = myString.size();
            if ((ptr.flags & OpenFile.APPEND) != 0) {
                ptr.pos = olen;
            }
            int pos = ptr.pos;
            if (pos == olen) {
                if (enc == EncodingUtils.ascii8bitEncoding(runtime) || encStr == EncodingUtils.ascii8bitEncoding(runtime)) {
                    EncodingUtils.encStrBufCat(runtime, myString, strByteList, enc);
                } else {
                    try {
                        RubyString unused = (RubyString) CAT_WITH_CODE_RANGE.invokeExact(myString, str);
                    } catch (Throwable t) {
                        throw new RuntimeException(t);
                    }
                }
            } else {
                strioExtend(pos, len);
                ByteList ptrByteList = myString.getByteList();
                System.arraycopy(strByteList.getUnsafeBytes(), strByteList.getBegin(), ptrByteList.getUnsafeBytes(), ptrByteList.begin() + pos, len);
            }
            ptr.pos = pos + len;
        } finally {
            unlock(ptr);
        }

        return len;
    }

    @JRubyMethod
    public IRubyObject set_encoding(ThreadContext context, IRubyObject ext_enc) {
        final Encoding enc;
        if ( ext_enc.isNil() ) {
            enc = EncodingUtils.defaultExternalEncoding(context.runtime);
        } else {
            enc = EncodingUtils.rbToEncoding(context, ext_enc);
        }

        StringIOData ptr = this.ptr;

        lock(ptr);
        try {
            ptr.enc = enc;

            // in read-only mode, StringIO#set_encoding no longer sets the encoding
            RubyString string;
            if (writable() && (string = ptr.string).getEncoding() != enc) {
                string.modify();
                string.setEncoding(enc);
            }
        } finally {
            unlock(ptr);
        }

        return this;
    }

    @JRubyMethod
    public IRubyObject set_encoding(ThreadContext context, IRubyObject enc, IRubyObject ignored) {
        return set_encoding(context, enc);
    }

    @JRubyMethod
    public IRubyObject set_encoding(ThreadContext context, IRubyObject enc, IRubyObject ignored1, IRubyObject ignored2) {
        return set_encoding(context, enc);
    }

    @JRubyMethod
    public IRubyObject external_encoding(ThreadContext context) {
        return context.runtime.getEncodingService().convertEncodingToRubyEncoding(getEncoding());
    }

    @JRubyMethod
    public IRubyObject internal_encoding(ThreadContext context) {
        return context.nil;
    }

    @JRubyMethod(name = "each_codepoint")
    public IRubyObject each_codepoint(ThreadContext context, Block block) {
        Ruby runtime = context.runtime;

        if (!block.isGiven()) return enumeratorize(runtime, this, "each_codepoint");

        checkReadable();

        StringIOData ptr = this.ptr;

        lock(ptr);
        try {
            final Encoding enc = getEncoding();
            RubyString myString = ptr.string;
            final ByteList string = myString.getByteList();
            final byte[] stringBytes = string.getUnsafeBytes();
            int begin = string.getBegin();
            for (; ; ) {
                int pos = ptr.pos;
                if (pos >= string.realSize()) return this;

                int c = StringSupport.codePoint(runtime, enc, stringBytes, begin + pos, stringBytes.length);
                int n = StringSupport.codeLength(enc, c);
                block.yield(context, runtime.newFixnum(c));
                ptr.pos = pos + n;
            }
        } finally {
            unlock(ptr);
        }
    }

    public static class GenericReadable {
        @JRubyMethod(name = "readchar")
        public static IRubyObject readchar(ThreadContext context, IRubyObject self) {
            IRubyObject c = self.callMethod(context, "getc");

            if (c.isNil()) throw context.runtime.newEOFError();

            return c;
        }

        @JRubyMethod(name = "readbyte")
        public static IRubyObject readbyte(ThreadContext context, IRubyObject self) {
            IRubyObject b = self.callMethod(context, "getbyte");

            if (b.isNil()) throw context.runtime.newEOFError();

            return b;
        }

        @JRubyMethod(name = "readline", optional = 1, writes = FrameField.LASTLINE)
        public static IRubyObject readline(ThreadContext context, IRubyObject self, IRubyObject[] args) {
            IRubyObject line = self.callMethod(context, "gets", args);

            if (line.isNil()) throw context.runtime.newEOFError();

            return line;
        }

        @JRubyMethod(name = {"sysread", "readpartial"}, optional = 2)
        public static IRubyObject sysread(ThreadContext context, IRubyObject self, IRubyObject[] args) {
            IRubyObject val = self.callMethod(context, "read", args);

            if (val.isNil()) throw context.runtime.newEOFError();

            return val;
        }

        @JRubyMethod(name = "read_nonblock", required = 1, optional = 2)
        public static IRubyObject read_nonblock(ThreadContext context, IRubyObject self, IRubyObject[] args) {
            int argc = Arity.checkArgumentCount(context, args, 1, 3);

            final Ruby runtime = context.runtime;

            boolean exception = true;
            IRubyObject opts = ArgsUtil.getOptionsArg(runtime, args);
            if (opts != context.nil) {
                args = ArraySupport.newCopy(args, argc - 1);
                exception = Helpers.extractExceptionOnlyArg(context, (RubyHash) opts);
            }

            IRubyObject val = self.callMethod(context, "read", args);
            if (val == context.nil) {
                if (!exception) return context.nil;
                throw runtime.newEOFError();
            }

            return val;
        }
    }

    public static class GenericWritable {
        @JRubyMethod(name = "<<", required = 1)
        public static IRubyObject append(ThreadContext context, IRubyObject self, IRubyObject arg) {
            // Claims conversion is done via 'to_s' in docs.
            self.callMethod(context, "write", arg);

            return self;
        }

        @JRubyMethod(name = "print", rest = true, writes = FrameField.LASTLINE)
        public static IRubyObject print(ThreadContext context, IRubyObject self, IRubyObject[] args) {
            return RubyIO.print(context, self, args);
        }

        @JRubyMethod(name = "printf", required = 1, rest = true)
        public static IRubyObject printf(ThreadContext context, IRubyObject self, IRubyObject[] args) {
            self.callMethod(context, "write", RubyKernel.sprintf(context, self, args));
            return context.nil;
        }

        @JRubyMethod(name = "puts", rest = true)
        public static IRubyObject puts(ThreadContext context, IRubyObject maybeIO, IRubyObject[] args) {
            // TODO: This should defer to RubyIO logic, but we don't have puts right there for 1.9
            Ruby runtime = context.runtime;
            if (args.length == 0) {
                RubyIO.write(context, maybeIO, RubyString.newStringShared(runtime, NEWLINE));
                return runtime.getNil();
            }

            for (int i = 0; i < args.length; i++) {
                RubyString line = null;

                if (!args[i].isNil()) {
                    IRubyObject tmp = args[i].checkArrayType();
                    if (!tmp.isNil()) {
                        @SuppressWarnings("unchecked")
                        RubyArray<IRubyObject> arr = (RubyArray<IRubyObject>) tmp;
                        if (runtime.isInspecting(arr)) {
                            line = runtime.newString("[...]");
                        } else {
                            inspectPuts(context, maybeIO, arr);
                            continue;
                        }
                    } else {
                        if (args[i] instanceof RubyString) {
                            line = (RubyString) args[i];
                        } else {
                            line = args[i].asString();
                        }
                    }
                }

                if (line != null) RubyIO.write(context, maybeIO, line);

                if (line == null || !line.getByteList().endsWith(NEWLINE)) {
                    RubyIO.write(context, maybeIO, RubyString.newStringShared(runtime, NEWLINE));
                }
            }

            return runtime.getNil();
        }

        private static IRubyObject inspectPuts(ThreadContext context, IRubyObject maybeIO, RubyArray<IRubyObject> array) {
            Ruby runtime = context.runtime;
            try {
                runtime.registerInspecting(array);
                return puts(context, maybeIO, array.toJavaArray());
            }
            finally {
                runtime.unregisterInspecting(array);
            }
        }

        @JRubyMethod(name = "syswrite", required = 1)
        public static IRubyObject syswrite(ThreadContext context, IRubyObject self, IRubyObject arg) {
            return RubyIO.write(context, self, arg);
        }

        @JRubyMethod(name = "write_nonblock", required = 1, optional = 1)
        public static IRubyObject syswrite_nonblock(ThreadContext context, IRubyObject self, IRubyObject[] args) {
            Arity.checkArgumentCount(context, args, 1, 2);

            Ruby runtime = context.runtime;

            ArgsUtil.getOptionsArg(runtime, args); // ignored as in MRI

            return syswrite(context, self, args[0]);
        }
    }

    public IRubyObject puts(ThreadContext context, IRubyObject[] args) {
        return GenericWritable.puts(context, this, args);
    }

    /* rb: check_modifiable */
    public void checkFrozen() {
        super.checkFrozen();
        checkInitialized();
    }

    private boolean readable() {
        return (flags & STRIO_READABLE) != 0
                && (ptr.flags & OpenFile.READABLE) != 0;
    }

    private boolean writable() {
        return (flags & STRIO_WRITABLE) != 0
                && (ptr.flags & OpenFile.WRITABLE) != 0;
    }

    private boolean closed() {
        return !((flags & STRIO_READWRITE) != 0
                && (ptr.flags & OpenFile.READWRITE) != 0);
    }

    /* rb: readable */
    private void checkReadable() {
        checkInitialized();
        if (!readable()) {
            throw getRuntime().newIOError("not opened for reading");
        }
    }

    /* rb: writable */
    private void checkWritable() {
        checkInitialized();
        if (!writable()) {
            throw getRuntime().newIOError("not opened for writing");
        }

        // Tainting here if we ever want it. (secure 4)
    }

    private void checkModifiable() {
        checkFrozen();
        if (ptr.string.isFrozen()) throw getRuntime().newIOError("not modifiable string");
    }

    private void checkInitialized() {
        if (ptr == null) {
            throw getRuntime().newIOError("uninitialized stream");
        }
    }

    private void checkFinalized() {
        if (ptr.string == null) {
            throw getRuntime().newIOError("not opened");
        }
    }

    private void checkOpen() {
        if (closed()) {
            throw getRuntime().newIOError(RubyIO.CLOSED_STREAM_MSG);
        }
    }

    private static void lock(StringIOData ptr) {
        while (!LOCKED_UPDATER.compareAndSet(ptr, 0, 1)); // lock
    }

    private static void unlock(StringIOData ptr) {
        ptr.locked = 0; // unlock
    }
}
