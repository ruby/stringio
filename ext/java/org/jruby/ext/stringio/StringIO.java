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
import org.jcodings.specific.USASCIIEncoding;
import org.jcodings.specific.UTF16BEEncoding;
import org.jcodings.specific.UTF16LEEncoding;
import org.jcodings.specific.UTF32BEEncoding;
import org.jcodings.specific.UTF32LEEncoding;
import org.jcodings.specific.UTF8Encoding;
import org.jruby.*;
import org.jruby.anno.FrameField;
import org.jruby.anno.JRubyClass;
import org.jruby.anno.JRubyMethod;
import org.jruby.ast.util.ArgsUtil;
import org.jruby.common.IRubyWarnings;
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
import org.jruby.util.func.ObjectObjectIntFunction;
import org.jruby.util.io.EncodingUtils;
import org.jruby.util.io.Getline;
import org.jruby.util.io.IOEncodable;
import org.jruby.util.io.OpenFile;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

import static java.lang.Byte.toUnsignedInt;
import static org.jruby.RubyEnumerator.enumeratorize;
import static org.jruby.runtime.Visibility.PRIVATE;
import static org.jruby.util.RubyStringBuilder.str;
import static org.jruby.util.RubyStringBuilder.types;

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
        volatile Object owner;
    }
    StringIOData ptr;

    private static final String
    STRINGIO_VERSION = "3.1.4";

    private static final int STRIO_READABLE = ObjectFlags.registry.newFlag(StringIO.class);
    private static final int STRIO_WRITABLE = ObjectFlags.registry.newFlag(StringIO.class);
    private static final int STRIO_READWRITE = (STRIO_READABLE | STRIO_WRITABLE);

    private static final AtomicReferenceFieldUpdater<StringIOData, Object> LOCKED_UPDATER = AtomicReferenceFieldUpdater.newUpdater(StringIOData.class, Object.class, "owner");

    private static final ThreadLocal<Object> VMODE_VPERM_TL = ThreadLocal.withInitial(() -> EncodingUtils.vmodeVperm(null, null));
    private static final ThreadLocal<int[]> FMODE_TL = ThreadLocal.withInitial(() -> new int[]{0});
    private static final int[] OFLAGS_UNUSED = new int[]{0};

    public static RubyClass createStringIOClass(final Ruby runtime) {
        RubyClass stringIOClass = runtime.defineClass(
                "StringIO", runtime.getObject(), StringIO::new);

        RubyString version = RubyString.newString(runtime, STRINGIO_VERSION);
        stringIOClass.defineConstant("VERSION", version);

        stringIOClass.defineConstant("MAX_LENGTH", RubyNumeric.int2fix(runtime, Integer.MAX_VALUE));

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
        if (enc != null) {
            return enc;
        }

        RubyString string = ptr.string;
        if (string != null && !string.isNil()) {
            return string.getEncoding();
        }

        return null;
    }

    public void setEncoding(Encoding enc) {
        ptr.enc = enc;
    }

    @JRubyMethod(name = "new", rest = true, meta = true)
    public static IRubyObject newInstance(ThreadContext context, IRubyObject recv, IRubyObject[] args, Block block) {
        RubyClass klass = (RubyClass) recv;

        warnIfBlock(context, block, klass);

        return klass.newInstance(context, args, block);
    }

    @JRubyMethod(name = "new", meta = true)
    public static IRubyObject newInstance(ThreadContext context, IRubyObject recv, Block block) {
        RubyClass klass = (RubyClass) recv;

        warnIfBlock(context, block, klass);

        return klass.newInstance(context, block);
    }

    @JRubyMethod(name = "new", meta = true)
    public static IRubyObject newInstance(ThreadContext context, IRubyObject recv, IRubyObject arg0, Block block) {
        RubyClass klass = (RubyClass) recv;

        warnIfBlock(context, block, klass);

        return klass.newInstance(context, arg0, block);
    }

    @JRubyMethod(name = "new", meta = true)
    public static IRubyObject newInstance(ThreadContext context, IRubyObject recv, IRubyObject arg0, IRubyObject arg1, Block block) {
        RubyClass klass = (RubyClass) recv;

        warnIfBlock(context, block, klass);

        return klass.newInstance(context, arg0, arg1, block);
    }

    @JRubyMethod(name = "new", meta = true)
    public static IRubyObject newInstance(ThreadContext context, IRubyObject recv, IRubyObject arg0, IRubyObject arg1, IRubyObject arg2, Block block) {
        RubyClass klass = (RubyClass) recv;

        warnIfBlock(context, block, klass);

        return klass.newInstance(context, arg0, arg1, arg2, block);
    }

    private static void warnIfBlock(ThreadContext context, Block block, RubyClass klass) {
        if (block.isGiven()) {
            Ruby runtime = context.runtime;
            IRubyObject className = types(runtime, klass);

            runtime.getWarnings().warn(IRubyWarnings.ID.BLOCK_NOT_ACCEPTED,
                    str(runtime, className, "::new() does not take block; use ", className, "::open() instead"));
        }
    }

    @JRubyMethod(meta = true, rest = true)
    public static IRubyObject open(ThreadContext context, IRubyObject recv, IRubyObject[] args, Block block) {
        StringIO strio = (StringIO)((RubyClass)recv).newInstance(context, args, Block.NULL_BLOCK);

        return yieldOrReturn(context, block, strio);
    }

    @JRubyMethod(meta = true)
    public static IRubyObject open(ThreadContext context, IRubyObject recv, Block block) {
        StringIO strio = (StringIO)((RubyClass)recv).newInstance(context, Block.NULL_BLOCK);

        return yieldOrReturn(context, block, strio);
    }

    @JRubyMethod(meta = true)
    public static IRubyObject open(ThreadContext context, IRubyObject recv, IRubyObject arg0, Block block) {
        StringIO strio = (StringIO)((RubyClass)recv).newInstance(context, arg0, Block.NULL_BLOCK);

        return yieldOrReturn(context, block, strio);
    }

    @JRubyMethod(meta = true)
    public static IRubyObject open(ThreadContext context, IRubyObject recv, IRubyObject arg0, IRubyObject arg1, Block block) {
        StringIO strio = (StringIO)((RubyClass)recv).newInstance(context, arg0, arg1, Block.NULL_BLOCK);

        return yieldOrReturn(context, block, strio);
    }

    @JRubyMethod(meta = true)
    public static IRubyObject open(ThreadContext context, IRubyObject recv, IRubyObject arg0, IRubyObject arg1, IRubyObject arg2, Block block) {
        StringIO strio = (StringIO)((RubyClass)recv).newInstance(context, arg0, arg1, arg2, Block.NULL_BLOCK);

        return yieldOrReturn(context, block, strio);
    }

    private static IRubyObject yieldOrReturn(ThreadContext context, Block block, StringIO strio) {
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

    @JRubyMethod(visibility = PRIVATE)
    public IRubyObject initialize(ThreadContext context) {
        if (ptr == null) {
            ptr = new StringIOData();
        }

        // does not dispatch quite right and is not really necessary for us
        //Helpers.invokeSuper(context, this, metaClass, "initialize", IRubyObject.NULL_ARRAY, Block.NULL_BLOCK);
        strioInit(context, 0, null, null);
        return this;
    }

    @JRubyMethod(visibility = PRIVATE)
    public IRubyObject initialize(ThreadContext context, IRubyObject arg0) {
        if (ptr == null) {
            ptr = new StringIOData();
        }

        // does not dispatch quite right and is not really necessary for us
        //Helpers.invokeSuper(context, this, metaClass, "initialize", IRubyObject.NULL_ARRAY, Block.NULL_BLOCK);
        strioInit(context, 1, arg0, null);
        return this;
    }

    @JRubyMethod(visibility = PRIVATE)
    public IRubyObject initialize(ThreadContext context, IRubyObject arg0, IRubyObject arg1) {
        if (ptr == null) {
            ptr = new StringIOData();
        }

        // does not dispatch quite right and is not really necessary for us
        //Helpers.invokeSuper(context, this, metaClass, "initialize", IRubyObject.NULL_ARRAY, Block.NULL_BLOCK);
        strioInit(context, 2, arg0, arg1);
        return this;
    }

    // MRI: strio_init
    private void strioInit(ThreadContext context, int argc, IRubyObject arg0, IRubyObject arg1) {
        Ruby runtime = context.runtime;
        IRubyObject string = context.nil;
        IRubyObject mode = context.nil;

        StringIOData ptr = this.ptr;

        boolean locked = lock(context, ptr);
        try {
            IRubyObject maybeOptions = context.nil;
            switch (argc) {
                case 1:
                    maybeOptions = ArgsUtil.getOptionsArg(runtime, arg0);
                    if (maybeOptions.isNil()) {
                        string = arg0;
                    }
                    break;
                case 2:
                    string = arg0;
                    maybeOptions = ArgsUtil.getOptionsArg(runtime, arg1);
                    if (maybeOptions.isNil()) {
                        mode = arg1;
                    }
                    break;
            }
            if (!maybeOptions.isNil()) {
                argc--;
            }
            Object vmodeAndVpermP = VMODE_VPERM_TL.get();
            EncodingUtils.vmode(vmodeAndVpermP, mode);
            IOEncodable.ConvConfig ioEncodable = new IOEncodable.ConvConfig();

            // switch to per-use oflags if it is ever used in the future
            EncodingUtils.extractModeEncoding(context, ioEncodable, vmodeAndVpermP, maybeOptions, OFLAGS_UNUSED, FMODE_TL.get());

            // clear shared vmodeVperm
            clearVmodeVperm(vmodeAndVpermP);

            ptr.flags = FMODE_TL.get()[0];

            if (!string.isNil()) {
                string = string.convertToString();
            } else if (argc == 0) {
                string = RubyString.newEmptyString(runtime, runtime.getDefaultInternalEncoding());
            }

            if (!string.isNil() && string.isFrozen()) {
                if ((ptr.flags & OpenFile.WRITABLE) != 0) {
                    throw runtime.newErrnoEACCESError("read-only string");
                }
            } else {
                if (mode.isNil()) {
                    ptr.flags |= OpenFile.WRITABLE;
                }
            }
            if (!string.isNil() && (ptr.flags & OpenFile.TRUNC) != 0) {
                ((RubyString) string).clear();
            }
            if (string instanceof RubyString) {
                ptr.string = (RubyString) string;
            }
            if (argc == 1 && !string.isNil()) {
                ptr.enc = ((RubyString) string).getEncoding();
            } else {
                ptr.enc = ioEncodable.enc;
            }
            ptr.pos = 0;
            ptr.lineno = 0;
            if ((ptr.flags & OpenFile.SETENC_BY_BOM) != 0) set_encoding_by_bom(context);
            // funky way of shifting readwrite flags into object flags
            flags |= (ptr.flags & OpenFile.READWRITE) * (STRIO_READABLE / OpenFile.READABLE);
        } finally {
            if (locked) unlock(ptr);
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

    @JRubyMethod(name = {"fcntl"}, rest = true, notImplemented = true)
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

        boolean locked = lock(context, ptr);
        try {
            ByteList bytes = ptr.string.getByteList();

            // Check the length every iteration, since
            // the block can modify this string.
            while (ptr.pos < bytes.length()) {
                // check readability for each loop, since it could get closed
                checkReadable();
                block.yield(context, runtime.newFixnum(bytes.get(ptr.pos++) & 0xFF));
            }
        } finally {
            if (locked) unlock(ptr);
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
        return ptr.string == null || ptr.pos >= ptr.string.size();
    }

    @JRubyMethod(name = "getc")
    public IRubyObject getc(ThreadContext context) {
        checkReadable();

        if (isEndOfString()) return context.nil;

        StringIOData ptr = this.ptr;

        boolean locked = lock(context, ptr);
        try {
            int start = ptr.pos;
            RubyString string = ptr.string;
            int total = 1 + StringSupport.bytesToFixBrokenTrailingCharacter(string.getByteList(), start + 1);

            ptr.pos += total;

            return context.runtime.newString(string.getByteList().makeShared(start, total));
        } finally {
            if (locked) unlock(ptr);
        }
    }

    @JRubyMethod(name = "getbyte")
    public IRubyObject getbyte(ThreadContext context) {
        checkReadable();

        if (isEndOfString()) return context.nil;

        int c;
        StringIOData ptr = this.ptr;
        boolean locked = lock(context, ptr);
        try {
            c = ptr.string.getByteList().get(ptr.pos++) & 0xFF;
        } finally {
            if (locked) unlock(ptr);
        }

        return context.runtime.newFixnum(c);
    }

    // MRI: strio_substr
    // must be called under lock
    private RubyString strioSubstr(Ruby runtime, int pos, int len, Encoding enc) {
        StringIOData ptr = this.ptr;

        final RubyString string = ptr.string;
        int rlen = string.size() - pos;

        if (len > rlen) len = rlen;
        if (len < 0) len = 0;
        if (len == 0) return RubyString.newEmptyString(runtime, enc);
        return encSubseq(runtime, string, pos, len, enc);
    }

    // MRI: enc_subseq
    private static RubyString encSubseq(Ruby runtime, RubyString str, int pos, int len, Encoding enc) {
        str = str.makeShared(runtime, pos, len);
        str.setEncoding(enc);
        return str;
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
        if (ptr.string == null) return context.nil;
        return Getline.getlineCall(context, GETLINE, this, getEncoding());
    }

    @JRubyMethod(name = "gets", writes = FrameField.LASTLINE)
    public IRubyObject gets(ThreadContext context, IRubyObject arg0) {
        if (ptr.string == null) return context.nil;
        return Getline.getlineCall(context, GETLINE, this, getEncoding(), arg0);
    }

    @JRubyMethod(name = "gets", writes = FrameField.LASTLINE)
    public IRubyObject gets(ThreadContext context, IRubyObject arg0, IRubyObject arg1) {
        if (ptr.string == null) return context.nil;
        return Getline.getlineCall(context, GETLINE, this, getEncoding(), arg0, arg1);
    }

    @JRubyMethod(name = "gets", writes = FrameField.LASTLINE)
    public IRubyObject gets(ThreadContext context, IRubyObject arg0, IRubyObject arg1, IRubyObject arg2) {
        if (ptr.string == null) return context.nil;
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
        if (self.isEndOfString()) return context.nil;

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

        StringIOData ptr = self.ptr;
        if (ptr.string == null || ptr.pos > ptr.string.size()) {
            return self;
        }

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

        StringIOData ptr = self.ptr;
        if (ptr.string == null || ptr.pos > ptr.string.size()) {
            return null;
        }

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

        boolean locked = lock(context, ptr);
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
            if (locked) unlock(ptr);
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
    public IRubyObject length(ThreadContext context) {
        checkInitialized();
        RubyString myString = ptr.string;
        if (myString == null) return RubyFixnum.zero(context.runtime);
        return getRuntime().newFixnum(myString.size());
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

    private void strioExtend(ThreadContext context, int pos, int len) {
        StringIOData ptr = this.ptr;

        boolean locked = lock(context, ptr);
        try {
            RubyString string = ptr.string;
            final int olen = string.size();
            long newSize = (long) pos + len;
            if (newSize > Integer.MAX_VALUE) {
                throw context.runtime.newArgumentError("string size too big");
            }
            if (newSize > olen) {
                string.resize((int) newSize);
                if (pos > olen) {
                    modifyString(string);
                    ByteList ptrByteList = string.getByteList();
                    // zero the gap
                    int begin = ptrByteList.getBegin();
                    Arrays.fill(ptrByteList.getUnsafeBytes(),
                            begin + olen,
                            begin + pos,
                            (byte) 0);
                }
            } else {
                modifyString(string);
            }
        } finally {
            if (locked) unlock(ptr);
        }
    }

    // MRI: strio_putc
    @JRubyMethod(name = "putc")
    public IRubyObject putc(ThreadContext context, IRubyObject ch) {
        Ruby runtime = context.runtime;
        checkWritable();
        IRubyObject str = null;

        checkModifiable();
        if (ch instanceof RubyString) {
            if (ptr.string == null) return context.nil;
            str = substrString((RubyString) ch, str, runtime);
        }
        else {
            byte c = RubyNumeric.num2chr(ch);
            if (ptr.string == null) return context.nil;
            str = RubyString.newString(runtime, new byte[]{c});
        }
        write(context, str);
        return ch;
    }

    public static final ByteList NEWLINE = ByteList.create("\n");

    @JRubyMethod(name = "read")
    public IRubyObject read(ThreadContext context) {
        return readCommon(context, 0, null, null);
    }

    @JRubyMethod(name = "read")
    public IRubyObject read(ThreadContext context, IRubyObject arg0) {
        return readCommon(context, 1, arg0, null);
    }

    @JRubyMethod(name = "read")
    public IRubyObject read(ThreadContext context, IRubyObject arg0, IRubyObject arg1) {
        return readCommon(context, 2, arg0, arg1);
    }

    @SuppressWarnings("fallthrough")
    private IRubyObject readCommon(ThreadContext context, int argc, IRubyObject arg0, IRubyObject arg1) {
        checkReadable();

        Ruby runtime = context.runtime;

        IRubyObject str = context.nil;
        boolean binary = false;
        StringIOData ptr = this.ptr;
        int pos = ptr.pos;

        boolean locked = lock(context, ptr);
        try {
            int len;
            final RubyString string;
            switch (argc) {
                case 2:
                    str = arg1;
                    if (!str.isNil()) {
                        str = str.convertToString();
                        ((RubyString) str).modify();
                    }
                case 1:
                    if (!arg0.isNil()) {
                        len = RubyNumeric.fix2int(arg0);

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
                    RubyString myString = ptr.string;
                    if (myString == null) {
                        return context.nil;
                    }
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
                    throw runtime.newArgumentError(argc, 0, 2);
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
                if (!binary) {
                    string.setEncoding(myString.getEncoding());
                }
            }

            ptr.pos += string.size();

            return string;
        } finally {
            if (locked) unlock(ptr);
        }
    }

    @JRubyMethod(name = "pread")
    public IRubyObject pread(ThreadContext context, IRubyObject arg0) {
        return preadCommon(context, 1, arg0, null, null);
    }

    @JRubyMethod(name = "pread")
    public IRubyObject pread(ThreadContext context, IRubyObject arg0, IRubyObject arg1) {
        return preadCommon(context, 2, arg0, arg1, null);
    }

    @JRubyMethod(name = "pread")
    public IRubyObject pread(ThreadContext context, IRubyObject arg0, IRubyObject arg1, IRubyObject arg2) {
        return preadCommon(context, 3, arg0, arg1, arg2);
    }

    @SuppressWarnings("fallthrough")
    private RubyString preadCommon(ThreadContext context, int argc, IRubyObject arg0, IRubyObject arg1, IRubyObject arg2) {
        IRubyObject str = context.nil;
        StringIOData ptr = this.ptr;
        Ruby runtime = context.runtime;
        int offset;
        final RubyString string;
        int len;
        checkReadable();

        switch (argc) {
            case 3:
                str = arg2;
                if (!str.isNil()) {
                    str = str.convertToString();
                    ((RubyString) str).modify();
                }
            case 2:
                len = RubyNumeric.fix2int(arg0);
                offset = RubyNumeric.fix2int(arg1);
                if (!arg0.isNil()) {
                    len = RubyNumeric.fix2int(arg0);

                    if (len < 0) {
                        throw runtime.newArgumentError("negative length " + len + " given");
                    }
                }
                break;
            default:
                throw runtime.newArgumentError(argc, 0, 2);
        }

        boolean locked = lock(context, ptr);
        try {
            if (len == 0) {
                if (str.isNil()) {
                    return RubyString.newEmptyString(runtime);
                }
                return (RubyString) str;
            }

            if (offset < 0) {
                throw runtime.newErrnoEINVALError("pread: Invalid offset argument");
            }

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
            if (locked) unlock(ptr);
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
    @JRubyMethod(name = "reopen")
    public IRubyObject reopen(ThreadContext context) {
        // reset the state
        strioInit(context, 0, null, null);
        return this;
    }

    // MRI: strio_reopen
    @JRubyMethod(name = "reopen")
    public IRubyObject reopen(ThreadContext context, IRubyObject arg0) {
        checkFrozen();

        if (!(arg0 instanceof RubyString)) {
            return initialize_copy(context, arg0);
        }

        // reset the state
        strioInit(context, 1, arg0, null);
        return this;
    }

    // MRI: strio_reopen
    @JRubyMethod(name = "reopen")
    public IRubyObject reopen(ThreadContext context, IRubyObject arg0, IRubyObject arg1) {
        checkFrozen();

        // reset the state
        strioInit(context, 2, arg0, arg1);
        return this;
    }

    @JRubyMethod(name = "rewind")
    public IRubyObject rewind(ThreadContext context) {
        checkInitialized();

        StringIOData ptr = this.ptr;

        boolean locked = lock(context, ptr);
        try {
            ptr.pos = 0;
            ptr.lineno = 0;
        } finally {
            if (locked) unlock(ptr);
        }

        return RubyFixnum.zero(context.runtime);
    }

    @JRubyMethod
    public IRubyObject seek(ThreadContext context, IRubyObject arg0) {
        return seekCommon(context, 1, arg0, null);
    }

    @JRubyMethod
    public IRubyObject seek(ThreadContext context, IRubyObject arg0, IRubyObject arg1) {
        return seekCommon(context, 2, arg0, arg1);
    }

    private RubyFixnum seekCommon(ThreadContext context, int argc, IRubyObject arg0, IRubyObject arg1) {
        checkFrozen();

        Ruby runtime = context.runtime;

        IRubyObject whence = context.nil;
        int offset = RubyNumeric.num2int(arg0);

        if (argc > 1 && !arg0.isNil()) {
            whence = arg1;
        }

        checkOpen();

        StringIOData ptr = this.ptr;

        boolean locked = lock(context, ptr);
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
            if (locked) unlock(ptr);
        }

        return RubyFixnum.zero(runtime);
    }

    @JRubyMethod(name = "string=", required = 1)
    public IRubyObject set_string(ThreadContext context, IRubyObject arg) {
        checkFrozen();
        StringIOData ptr = this.ptr;

        boolean locked = lock(context, ptr);
        try {
            ptr.flags &= ~OpenFile.READWRITE;
            RubyString str = arg.convertToString();
            ptr.flags = str.isFrozen() ? OpenFile.READABLE : OpenFile.READWRITE;
            ptr.pos = 0;
            ptr.lineno = 0;
            return ptr.string = str;
        } finally {
            if (locked) unlock(ptr);
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

    @JRubyMethod(name = "truncate", required = 1)
    public IRubyObject truncate(ThreadContext context, IRubyObject len) {
        checkWritable();

        int l = RubyFixnum.fix2int(len);
        StringIOData ptr = this.ptr;
        RubyString string = ptr.string;

        boolean locked = lock(context, ptr);
        try {
            if (l < 0) {
                throw context.runtime.newErrnoEINVALError("negative legnth");
            }
            if (string == null) {
                return RubyFixnum.zero(context.runtime);
            }
            int plen = string.size();
            string.resize(l);
            ByteList buf = string.getByteList();
            if (plen < l) {
                // zero the gap
                Arrays.fill(buf.getUnsafeBytes(), buf.getBegin() + plen, buf.getBegin() + l, (byte) 0);
            }
        } finally {
            if (locked) unlock(ptr);
        }

        return RubyFixnum.zero(context.runtime);
    }

    @JRubyMethod(name = "ungetc")
    public IRubyObject ungetc(ThreadContext context, IRubyObject arg) {
        Encoding enc, enc2;

        checkModifiable();
        checkReadable();

        if (ptr.string == null) return context.nil;

        if (arg.isNil()) return arg;
        if (arg instanceof RubyInteger) {
            int len, cc = RubyNumeric.num2int(arg);
            byte[] buf = new byte[16];

            enc = getEncoding();
            len = enc.codeToMbcLength(cc);
            if (len <= 0) EncodingUtils.encUintChr(context, cc, enc);
            enc.codeToMbc(cc, buf, 0);
            ungetbyteCommon(context, buf, 0, len);
        } else {
            arg = arg.convertToString();
            enc = getEncoding();
            RubyString argStr = (RubyString) arg;
            enc2 = argStr.getEncoding();
            if (enc != enc2 && enc != ASCIIEncoding.INSTANCE) {
                argStr = EncodingUtils.strConvEnc(context, argStr, enc2, enc);
            }
            ungetbyteCommon(context, argStr);
        }

        return context.nil;
    }

    private void ungetbyteCommon(ThreadContext context, int c) {
        StringIOData ptr = this.ptr;

        boolean locked = lock(context, ptr);
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
            if (locked) unlock(ptr);
        }
    }

    private void ungetbyteCommon(ThreadContext context, RubyString ungetBytes) {
        ByteList ungetByteList = ungetBytes.getByteList();
        ungetbyteCommon(context, ungetByteList.unsafeBytes(), ungetByteList.begin(), ungetByteList.realSize());
    }

    private void ungetbyteCommon(ThreadContext context, byte[] ungetBytes, int cp, int cl) {
        if (cl == 0) return;

        StringIOData ptr = this.ptr;

        boolean locked = lock(context, ptr);
        try {
            int pos = ptr.pos, len, rest;
            RubyString str = ptr.string;
            ByteList strBytelist;
            byte[] strBytes;
            int s;

            len = str.size();
            rest = pos - len;
            if (cl > pos) {
                int ex = cl - (rest < 0 ? pos : len);
                str.modifyExpand(len + ex);
                strBytelist = str.getByteList();
                strBytes = strBytelist.unsafeBytes();
                s = strBytelist.begin();
                strBytelist.setRealSize(len + ex);
                if (rest < 0) System.arraycopy(strBytes, s + pos, strBytes, s + cl, -rest);
                pos = 0;
            }
            else {
                if (rest > 0) {
                    str.modifyExpand(len + rest);
                    strBytelist = str.getByteList();
                    strBytelist.setRealSize(len + rest);
                } else {
                    strBytelist = str.getByteList();
                }
                strBytes = strBytelist.unsafeBytes();
                s = strBytelist.begin();
                if (rest > cl) Arrays.fill(strBytes, len, rest - cl, (byte) 0);
                pos -= cl;
            }
            if (ungetBytes != null) {
                System.arraycopy(ungetBytes, cp, strBytes, s + pos, cl);
            } else {
                System.arraycopy(strBytes, s, strBytes, s + pos, cl);
            }
            ptr.pos = pos;
        } finally {
            if (locked) unlock(ptr);
        }
    }

    @JRubyMethod
    public IRubyObject ungetbyte(ThreadContext context, IRubyObject arg) {
        // TODO: Not a line-by-line port.
        checkReadable();

        if (arg.isNil()) return arg;

        checkModifiable();
        if (ptr.string == null) return context.nil;

        if (arg instanceof RubyInteger) {
            ungetbyteCommon(context, ((RubyInteger) ((RubyInteger) arg).op_mod(context, 256)).getIntValue());
        } else {
            ungetbyteCommon(context, arg.convertToString());
        }

        return context.nil;
    }

    // MRI: strio_write
    @JRubyMethod(name = "write")
    public IRubyObject write(ThreadContext context, IRubyObject arg) {
        Ruby runtime = context.runtime;
        return RubyFixnum.newFixnum(runtime, stringIOWrite(context, runtime, arg));
    }

    @JRubyMethod(name = "write")
    public IRubyObject write(ThreadContext context, IRubyObject arg0, IRubyObject arg1) {
        Ruby runtime = context.runtime;
        long len = 0;
        len += stringIOWrite(context, runtime, arg0);
        len += stringIOWrite(context, runtime, arg1);
        return RubyFixnum.newFixnum(runtime, len);
    }

    @JRubyMethod(name = "write")
    public IRubyObject write(ThreadContext context, IRubyObject arg0, IRubyObject arg1, IRubyObject arg2) {
        Ruby runtime = context.runtime;
        long len = 0;
        len += stringIOWrite(context, runtime, arg0);
        len += stringIOWrite(context, runtime, arg1);
        len += stringIOWrite(context, runtime, arg2);
        return RubyFixnum.newFixnum(runtime, len);
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
    private static final MethodHandle MODIFY_AND_CLEAR_CODE_RANGE;
    private static final MethodHandle SUBSTR_ENC;

    static {
        MethodHandle cat, modify, substr;
        MethodHandles.Lookup lookup = MethodHandles.lookup();
        try {
            cat = lookup.findVirtual(RubyString.class, "catWithCodeRange", MethodType.methodType(RubyString.class, RubyString.class));
            modify = lookup.findVirtual(RubyString.class, "modifyAndClearCodeRange", MethodType.methodType(void.class));
            substr = lookup.findVirtual(RubyString.class, "substrEnc", MethodType.methodType(IRubyObject.class, Ruby.class, int.class, int.class));
        } catch (NoSuchMethodException | IllegalAccessException ex) {
            try {
                cat = lookup.findVirtual(RubyString.class, "cat19", MethodType.methodType(RubyString.class, RubyString.class));
                modify = lookup.findVirtual(RubyString.class, "modify19", MethodType.methodType(void.class));
                substr = lookup.findVirtual(RubyString.class, "substr19", MethodType.methodType(IRubyObject.class, Ruby.class, int.class, int.class));
            } catch (NoSuchMethodException | IllegalAccessException ex2) {
                throw new ExceptionInInitializerError(ex2);
            }
        }

        CAT_WITH_CODE_RANGE = cat;
        MODIFY_AND_CLEAR_CODE_RANGE = modify;
        SUBSTR_ENC = substr;
    }

    private static void catString(RubyString myString, RubyString str) {
        try {
            RubyString unused = (RubyString) CAT_WITH_CODE_RANGE.invokeExact(myString, str);
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    private static void modifyString(RubyString string) {
        try {
            MODIFY_AND_CLEAR_CODE_RANGE.invokeExact(string);
        } catch (Throwable t) {
            Helpers.throwException(t);
        }
    }

    private static IRubyObject substrString(RubyString ch, IRubyObject str, Ruby runtime) {
        try {
            str = (IRubyObject) SUBSTR_ENC.invokeExact(ch, runtime, 0, 1);
        } catch (Throwable t) {
            Helpers.throwException(t);
        }
        return str;
    }

    // MRI: strio_write
    private long stringIOWrite(ThreadContext context, Ruby runtime, IRubyObject arg) {
        checkWritable();

        RubyString str = arg.asString();
        int len, olen;

        StringIOData ptr = this.ptr;

        boolean locked = lock(context, ptr);
        try {
            final Encoding enc = getEncoding();
            if (enc == null) return 0;
            final Encoding encStr = str.getEncoding();
            if (enc != encStr && enc != EncodingUtils.ascii8bitEncoding(runtime)
                    // this is a hack because we don't seem to handle incoming ASCII-8BIT properly in transcoder
                    && encStr != ASCIIEncoding.INSTANCE) {
                RubyString converted = EncodingUtils.strConvEnc(context, str, encStr, enc);
                if (converted == str && encStr != ASCIIEncoding.INSTANCE && encStr != USASCIIEncoding.INSTANCE) { /* conversion failed */
                    ptr.string.checkEncoding(str);
                }
                str = converted;
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
                    catString(myString, str);
                }
            } else {
                strioExtend(context, pos, len);
                modifyString(myString);
                ByteList ptrByteList = myString.getByteList();
                System.arraycopy(strByteList.getUnsafeBytes(), strByteList.getBegin(), ptrByteList.getUnsafeBytes(), ptrByteList.begin() + pos, len);
            }
            ptr.pos = pos + len;
        } finally {
            if (locked) unlock(ptr);
        }

        return len;
    }

    @JRubyMethod
    public IRubyObject set_encoding(ThreadContext context, IRubyObject ext_enc) {
        Encoding enc;
        if ( ext_enc.isNil() ) {
            enc = EncodingUtils.defaultExternalEncoding(context.runtime);
        } else {
            enc = context.runtime.getEncodingService().getEncodingFromObjectNoError(ext_enc);
            if (enc == null) {
                IOEncodable convconfig = new IOEncodable.ConvConfig();
                Object vmodeAndVpermP = VMODE_VPERM_TL.get();
                EncodingUtils.vmode(vmodeAndVpermP, ext_enc.convertToString().prepend(context, context.runtime.newString("r:")));
                EncodingUtils.extractModeEncoding(context, convconfig, vmodeAndVpermP, context.nil, OFLAGS_UNUSED, FMODE_TL.get());
                clearVmodeVperm(vmodeAndVpermP);
                enc = convconfig.getEnc2();
            }
        }

        StringIOData ptr = this.ptr;

        boolean locked = lock(context, ptr);
        try {
            ptr.enc = enc;

            // in read-only mode, StringIO#set_encoding no longer sets the encoding
            RubyString string = ptr.string;
            if (string != null && writable() && string.getEncoding() != enc) {
                string.modify();
                string.setEncoding(enc);
            }
        } finally {
            if (locked) unlock(ptr);
        }

        return this;
    }

    private static void clearVmodeVperm(Object vmodeAndVpermP) {
        EncodingUtils.vmode(vmodeAndVpermP, null);
        EncodingUtils.vperm(vmodeAndVpermP, null);
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
    public IRubyObject set_encoding_by_bom(ThreadContext context) {
        if (setEncodingByBOM(context) == null) return context.nil;

        return context.runtime.getEncodingService().convertEncodingToRubyEncoding(ptr.enc);
    }

    private Encoding setEncodingByBOM(ThreadContext context) {
        Encoding enc = detectBOM(context, ptr.string, (ctx, enc2, bomlen) -> {
            ptr.pos = bomlen;
            if (writable()) {
                ptr.string.setEncoding(enc2);
            }
            return enc2;
        });
        ptr.enc = enc;
        return enc;
    }

    private static Encoding detectBOM(ThreadContext context, RubyString str, ObjectObjectIntFunction<ThreadContext, Encoding, Encoding> callback) {
        int p;
        int len;

        ByteList byteList = str.getByteList();
        byte[] bytes = byteList.unsafeBytes();
        p = byteList.begin();
        len = byteList.realSize();

        if (len < 1) return null;
        switch (toUnsignedInt(bytes[p])) {
            case 0xEF:
                if (len < 3) break;
                if (toUnsignedInt(bytes[p + 1]) == 0xBB && toUnsignedInt(bytes[p + 2]) == 0xBF) {
                    return callback.apply(context, UTF8Encoding.INSTANCE, 3);
                }
                break;

            case 0xFE:
                if (len < 2) break;
                if (toUnsignedInt(bytes[p + 1]) == 0xFF) {
                    return callback.apply(context, UTF16BEEncoding.INSTANCE, 2);
                }
                break;

            case 0xFF:
                if (len < 2) break;
                if (toUnsignedInt(bytes[p + 1]) == 0xFE) {
                    if (len >= 4 && toUnsignedInt(bytes[p + 2]) == 0 && toUnsignedInt(bytes[p + 3]) == 0) {
                        return callback.apply(context, UTF32LEEncoding.INSTANCE, 4);
                    }
                    return callback.apply(context, UTF16LEEncoding.INSTANCE, 2);
                }
                break;

            case 0:
                if (len < 4) break;
                if (toUnsignedInt(bytes[p + 1]) == 0 && toUnsignedInt(bytes[p + 2]) == 0xFE && toUnsignedInt(bytes[p + 3]) == 0xFF) {
                    return callback.apply(context, UTF32BEEncoding.INSTANCE, 4);
                }
                break;
        }
        return callback.apply(context, null, 0);
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

        boolean locked = lock(context, ptr);
        try {
            final Encoding enc = getEncoding();
            RubyString myString = ptr.string;
            final ByteList string = myString.getByteList();
            final byte[] stringBytes = string.getUnsafeBytes();
            int begin = string.getBegin();
            for (; ; ) {
                // check readability for each loop, since it could get closed
                checkReadable();

                int pos = ptr.pos;
                if (pos >= string.realSize()) return this;

                int c = StringSupport.codePoint(runtime, enc, stringBytes, begin + pos, stringBytes.length);
                int n = StringSupport.codeLength(enc, c);
                ptr.pos = pos + n;
                block.yield(context, runtime.newFixnum(c));
            }
        } finally {
            if (locked) unlock(ptr);
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

        @JRubyMethod(name = "readline", writes = FrameField.LASTLINE)
        public static IRubyObject readline(ThreadContext context, IRubyObject self) {
            IRubyObject line = self.callMethod(context, "gets");

            if (line.isNil()) throw context.runtime.newEOFError();

            return line;
        }

        @JRubyMethod(name = "readline", writes = FrameField.LASTLINE)
        public static IRubyObject readline(ThreadContext context, IRubyObject self, IRubyObject arg0) {
            IRubyObject line = self.callMethod(context, "gets", arg0);

            if (line.isNil()) throw context.runtime.newEOFError();

            return line;
        }

        @JRubyMethod(name = {"sysread", "readpartial"})
        public static IRubyObject sysread(ThreadContext context, IRubyObject self) {
            IRubyObject val = self.callMethod(context, "read");

            if (val.isNil()) throw context.runtime.newEOFError();

            return val;
        }

        @JRubyMethod(name = {"sysread", "readpartial"})
        public static IRubyObject sysread(ThreadContext context, IRubyObject self, IRubyObject arg0) {
            IRubyObject val = Helpers.invoke(context, self, "read", arg0);

            if (val.isNil()) throw context.runtime.newEOFError();

            return val;
        }

        @JRubyMethod(name = {"sysread", "readpartial"})
        public static IRubyObject sysread(ThreadContext context, IRubyObject self, IRubyObject arg0, IRubyObject arg1) {
            IRubyObject val = Helpers.invoke(context, self, "read", arg0, arg1);

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
        @JRubyMethod(name = "<<")
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
            // TODO: This should defer to RubyIO logic?
            Ruby runtime = context.runtime;
            if (args.length == 0) {
                RubyIO.write(context, maybeIO, RubyString.newStringShared(runtime, NEWLINE));
                return runtime.getNil();
            }

            for (int i = 0; i < args.length; i++) {
                putsArg(context, maybeIO, args[i], runtime);
            }

            return context.nil;
        }

        @JRubyMethod(name = "puts")
        public static IRubyObject puts(ThreadContext context, IRubyObject maybeIO) {
            // TODO: This should defer to RubyIO logic?
            RubyIO.write(context, maybeIO, RubyString.newStringShared(context.runtime, NEWLINE));
            return context.nil;
        }

        @JRubyMethod(name = "puts")
        public static IRubyObject puts(ThreadContext context, IRubyObject maybeIO, IRubyObject arg0) {
            // TODO: This should defer to RubyIO logic?
            putsArg(context, maybeIO, arg0, context.runtime);

            return context.nil;
        }

        @JRubyMethod(name = "puts")
        public static IRubyObject puts(ThreadContext context, IRubyObject maybeIO, IRubyObject arg0, IRubyObject arg1) {
            // TODO: This should defer to RubyIO logic?
            Ruby runtime = context.runtime;

            putsArg(context, maybeIO, arg0, runtime);
            putsArg(context, maybeIO, arg1, runtime);

            return context.nil;
        }

        @JRubyMethod(name = "puts")
        public static IRubyObject puts(ThreadContext context, IRubyObject maybeIO, IRubyObject arg0, IRubyObject arg1, IRubyObject arg2) {
            // TODO: This should defer to RubyIO logic?
            Ruby runtime = context.runtime;

            putsArg(context, maybeIO, arg0, runtime);
            putsArg(context, maybeIO, arg1, runtime);
            putsArg(context, maybeIO, arg2, runtime);

            return context.nil;
        }

        private static void putsArg(ThreadContext context, IRubyObject maybeIO, IRubyObject arg, Ruby runtime) {
            RubyString line = null;
            if (!arg.isNil()) {
                IRubyObject tmp = arg.checkArrayType();
                if (!tmp.isNil()) {
                    @SuppressWarnings("unchecked")
                    RubyArray<IRubyObject> arr = (RubyArray<IRubyObject>) tmp;
                    if (runtime.isInspecting(arr)) {
                        line = runtime.newString("[...]");
                    } else {
                        inspectPuts(context, maybeIO, arr);
                        return;
                    }
                } else {
                    if (arg instanceof RubyString) {
                        line = (RubyString) arg;
                    } else {
                        line = arg.asString();
                    }
                }
            }

            if (line != null) RubyIO.write(context, maybeIO, line);

            if (line == null || !line.getByteList().endsWith(NEWLINE)) {
                RubyIO.write(context, maybeIO, RubyString.newStringShared(runtime, NEWLINE));
            }
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

        @JRubyMethod(name = "syswrite")
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

    private void checkOpen() {
        if (closed()) {
            throw getRuntime().newIOError(RubyIO.CLOSED_STREAM_MSG);
        }
    }

    private static boolean lock(ThreadContext context, StringIOData ptr) {
        if (ptr.owner == context) return false;
        while (!LOCKED_UPDATER.compareAndSet(ptr, null, context)); // lock
        return true;
    }

    private static void unlock(StringIOData ptr) {
        ptr.owner = null; // unlock
    }
}
