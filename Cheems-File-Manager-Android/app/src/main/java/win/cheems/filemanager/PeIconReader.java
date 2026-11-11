package win.cheems.filemanager;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Read-only extractor for a Windows EXE/DLL's actual RT_GROUP_ICON/RT_ICON
 * resources. It NEVER executes the PE file. Validated offsets and strict
 * bounds protect the app against malformed or extremely large resources.
 *
 * Supports embedded PNG icons and common 32/24-bit uncompressed DIB icons;
 * if a file has unsupported icon formats, normal CFM glyphs are retained.
 */
final class PeIconReader {
    private PeIconReader(){}

    private static final int MAX_PARTS=96;
    private static final int MAX_ICON_BYTES=2*1024*1024;

    static String readIcon(File file) {
        if(!file.isFile() || file.length()<512 || file.length()>1024L*1024*1024)
            return null;
        try(RandomAccessFile reader=new RandomAccessFile(file,"r")){
            Pe pe=new Pe(reader);
            int groupDir=pe.resourceType(14);
            int iconDir=pe.resourceType(3);
            if(groupDir<0 || iconDir<0)return null;
            List<IconItem> candidates=new ArrayList<>();
            for(Entry group:pe.entries(groupDir)){
                int data=pe.firstData(group);
                if(data<0)continue;
                byte[] groupInfo=pe.readResource(data,8192);
                if(groupInfo==null || groupInfo.length<6 || word(groupInfo,2)!=1)continue;
                int count=Math.min(word(groupInfo,4),50);
                for(int i=0;i<count;i++){
                    int offset=6+i*14;
                    if(offset+14>groupInfo.length)break;
                    int width=groupInfo[offset]&255;
                    int height=groupInfo[offset+1]&255;
                    int bits=word(groupInfo,offset+6);
                    int iconId=word(groupInfo,offset+12);
                    int w=width==0?256:width;
                    int h=height==0?256:height;
                    if(iconId!=0 && w>=16 && w<=256 && h>=16 && h<=256)
                        candidates.add(new IconItem(iconId,w,h,bits));
                }
            }
            Collections.sort(candidates,Comparator.comparingInt(
                (IconItem c)->Math.min(c.w,c.h)*100+c.bits).reversed());
            for(IconItem candidate:candidates){
                for(Entry id:pe.entries(iconDir)){
                    if(id.name!=candidate.id)continue;
                    int data=pe.firstData(id);
                    if(data<0)continue;
                    byte[] raw=pe.readResource(data,MAX_ICON_BYTES);
                    if(raw==null)continue;
                    Bitmap img=decodeIcon(raw,candidate.w,candidate.h);
                    if(img==null)continue;
                    try {
                        Bitmap resized=Bitmap.createScaledBitmap(img,48,48,true);
                        ByteArrayOutputStream buffer=new ByteArrayOutputStream();
                        boolean ok=resized.compress(Bitmap.CompressFormat.PNG,95,buffer);
                        if(resized!=img)resized.recycle();
                        if(ok && buffer.size()>30 && buffer.size()<150000)
                            return "data:image/png;base64,"+
                                Base64.encodeToString(buffer.toByteArray(),Base64.NO_WRAP);
                    } finally {
                        img.recycle();
                    }
                }
            }
        } catch(Exception ignored) {
            // An unknown, packed, corrupted or encrypted PE uses the normal icon.
        }
        return null;
    }

    private static Bitmap decodeIcon(byte[] raw,int w,int h) {
        if(raw.length>=24 && (raw[0]&255)==137 && raw[1]==80 &&
           raw[2]==78 && raw[3]==71) {
            Bitmap img=BitmapFactory.decodeByteArray(raw,0,raw.length);
            if(img==null || img.getWidth()>1024 || img.getHeight()>1024) {
                if(img!=null)img.recycle();
                return null;
            }
            return img;
        }
        // Windows RT_ICON is raw DIB: header, bottom-up XOR image, AND mask.
        // biHeight includes the XOR and mask heights, hence division by 2.
        if(raw.length<40 || little(raw,0)!=40)return null;
        int width=little(raw,4);
        int doubleHeight=little(raw,8);
        int bitDepth=word(raw,14),compression=little(raw,16);
        if(width<1 || width>256 || doubleHeight<2 ||
           (doubleHeight&1)!=0 || compression!=0 ||
           (bitDepth!=24 && bitDepth!=32))return null;
        int height=doubleHeight/2;
        if(height<1 || height>256 || w!=width || h!=height)return null;
        int rowBytes=((width*bitDepth+31)/32)*4;
        long needed=40L+(long)height*rowBytes;
        if(needed>raw.length)return null;
        int[] pixels=new int[width*height];
        boolean anyAlpha=false;
        for(int y=0;y<height;y++){
            int bottomRow=height-1-y;
            int row=40+bottomRow*rowBytes;
            for(int x=0;x<width;x++){
                int src=row+x*(bitDepth/8);
                if(src+bitDepth/8>raw.length)return null;
                int b=raw[src]&255,g=raw[src+1]&255,r=raw[src+2]&255;
                int a=bitDepth==32?(raw[src+3]&255):255;
                if(bitDepth==32 && a!=0)anyAlpha=true;
                pixels[y*width+x]=Color.argb(a,r,g,b);
            }
        }
        // When all XOR alpha channels are zero, transparency is represented
        // by the 1-bit AND mask.
        if(bitDepth==32 && !anyAlpha){
            int mask=(int)needed;
            int andStride=((width+31)/32)*4;
            for(int y=0;y<height;y++){
                int row=mask+(height-1-y)*andStride;
                for(int x=0;x<width;x++){
                    int pos=row+x/8;
                    boolean transparent=pos<raw.length &&
                        ((raw[pos]>>(7-x%8))&1)!=0;
                    pixels[y*width+x]=(pixels[y*width+x]&0x00ffffff) |
                        (transparent?0:0xff000000);
                }
            }
        }
        return Bitmap.createBitmap(pixels,width,height,Bitmap.Config.ARGB_8888);
    }

    private static int word(byte[] b,int at){
        if(at<0 || at+2>b.length)return 0;
        return (b[at]&255)|((b[at+1]&255)<<8);
    }
    private static int little(byte[] b,int at){
        if(at<0 || at+4>b.length)return 0;
        return (b[at]&255)|((b[at+1]&255)<<8)|
            ((b[at+2]&255)<<16)|((b[at+3]&255)<<24);
    }
    private static final class IconItem {
        final int id,w,h,bits;
        IconItem(int id,int w,int h,int bits) {
            this.id=id;this.w=w;this.h=h;this.bits=bits;
        }
    }
    private static final class Entry {
        final int name,target;
        final boolean directory;
        Entry(int name,int target,boolean directory) {
            this.name=name;this.target=target;this.directory=directory;
        }
    }

    private static final class Pe {
        final RandomAccessFile file;
        final long length;
        final long base;
        final int[] sectionRva;
        final int[] sectionVirtual;
        final int[] sectionRaw;
        final int[] sectionLength;

        Pe(RandomAccessFile file) throws IOException {
            this.file=file; length=file.length();
            if(u16(0)!=0x5A4D)throw new IOException("Not MZ");
            long pe=unsigned(u32(0x3c));
            if(pe<64 || pe+256>length || u32(pe)!=0x00004550)
                throw new IOException("Not PE");
            int count=u16(pe+6);
            int optionalSize=u16(pe+20);
            if(count<1||count>96 || optionalSize<112)throw new IOException("Bad PE header");
            long optional=pe+24;
            int magic=u16(optional);
            int directoryOffset=magic==0x10b?96:magic==0x20b?112:-1;
            if(directoryOffset<0 || optionalSize<directoryOffset+24)
                throw new IOException("No PE resource directory");
            int resourceRva=u32(optional+directoryOffset+16);
            if(resourceRva<=0)throw new IOException("No icon resources");
            sectionRva=new int[count];sectionVirtual=new int[count];
            sectionRaw=new int[count];sectionLength=new int[count];
            long sectionTable=optional+optionalSize;
            if(sectionTable+40L*count>length)throw new IOException("Bad PE sections");
            for(int i=0;i<count;i++){
                long p=sectionTable+40L*i;
                sectionVirtual[i]=u32(p+8);
                sectionRva[i]=u32(p+12);
                sectionLength[i]=u32(p+16);
                sectionRaw[i]=u32(p+20);
            }
            long res=offsetForRva(resourceRva);
            if(res<0)throw new IOException("Invalid resource RVA");
            base=res;
        }

        int u16(long where)throws IOException {
            if(where<0||where+2>length)throw new IOException("PE offset overflow");
            file.seek(where);
            return file.readUnsignedByte() | (file.readUnsignedByte()<<8);
        }
        int u32(long where)throws IOException {
            long lo=u16(where),hi=u16(where+2);
            return (int)(lo|(hi<<16));
        }
        long unsigned(int x){return x&0xffffffffL;}
        long offsetForRva(int rva){
            long seek=unsigned(rva);
            for(int i=0;i<sectionRva.length;i++){
                long begin=unsigned(sectionRva[i]);
                long size=Math.max(unsigned(sectionVirtual[i]),unsigned(sectionLength[i]));
                long diff=seek-begin;
                if(diff>=0 && diff<unsigned(sectionLength[i]) &&
                   diff<size) {
                    long at=unsigned(sectionRaw[i])+diff;
                    if(at>=0 && at<length)return at;
                }
            }
            return -1;
        }
        List<Entry> entries(int relative)throws IOException {
            long dir=base+unsigned(relative);
            if(relative<0 || dir<base || dir+16>length)
                throw new IOException("Resource directory outside file");
            int count=u16(dir+12)+u16(dir+14);
            if(count<0||count>MAX_PARTS||dir+16L+count*8>length)
                throw new IOException("Oversized resource directory");
            List<Entry> children=new ArrayList<>();
            for(int n=0;n<count;n++){
                long pos=dir+16L+n*8;
                int id=u32(pos);
                int raw=u32(pos+4);
                children.add(new Entry(id&65535,raw&0x7fffffff,raw<0));
            }
            return children;
        }
        int resourceType(int id)throws IOException {
            for(Entry entry:entries(0)){
                if(entry.name==id && entry.directory)return entry.target;
            }
            return -1;
        }
        int firstData(Entry root)throws IOException {
            if(!root.directory)return -1;
            for(Entry child:entries(root.target)){
                if(!child.directory)return child.target;
                for(Entry leaf:entries(child.target)){
                    if(!leaf.directory)return leaf.target;
                }
            }
            return -1;
        }
        byte[] readResource(int relative,int maximum)throws IOException {
            long p=base+unsigned(relative);
            if(relative<0||p+16>length)return null;
            int rva=u32(p),size=u32(p+4);
            if(size<=0 || size>maximum)return null;
            long fileOffset=offsetForRva(rva);
            if(fileOffset<0 || fileOffset+size>length)return null;
            byte[] result=new byte[size];
            file.seek(fileOffset);file.readFully(result);
            return result;
        }
    }
}
