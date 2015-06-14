/*
 * Copyright (C) 2012 Alex Kuiper
 *
 * This file is part of PageTurner
 *
 * PageTurner is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * PageTurner is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with PageTurner.  If not, see <http://www.gnu.org/licenses/>.*
 */
package net.nightwhistler.pageturner.fragment;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.channels.FileChannel;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONArray;
import net.nightwhistler.pageturner.TextUtil;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.ByteBuffer;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import java.io.InputStream;
import android.content.res.AssetManager;
import android.content.Intent;
import android.database.sqlite.SQLiteException;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import com.actionbarsherlock.app.ActionBar;
import com.actionbarsherlock.app.SherlockFragmentActivity;
import com.actionbarsherlock.view.Menu;
import com.actionbarsherlock.view.MenuInflater;
import com.actionbarsherlock.view.MenuItem;
import com.actionbarsherlock.widget.SearchView;
import com.github.rtyley.android.sherlock.roboguice.fragment.RoboSherlockFragment;
import com.google.inject.Inject;
import com.parse.ParseException;
import com.parse.ParseFile;
import com.parse.ParseObject;
import com.parse.ParseQuery;

import jedi.functional.FunctionalPrimitives;
import jedi.option.Option;
import net.nightwhistler.htmlspanner.HtmlSpanner;
import net.nightwhistler.pageturner.Configuration;
import net.nightwhistler.pageturner.Configuration.ColourProfile;
import net.nightwhistler.pageturner.Configuration.LibrarySelection;
import net.nightwhistler.pageturner.Configuration.LibraryView;
import net.nightwhistler.pageturner.PlatformUtil;
import net.nightwhistler.pageturner.R;
import net.nightwhistler.pageturner.activity.*;
import net.nightwhistler.ui.DialogFactory;
import net.nightwhistler.ui.UiUtils;
import net.nightwhistler.pageturner.library.*;
import net.nightwhistler.pageturner.scheduling.QueueableAsyncTask;
import net.nightwhistler.pageturner.scheduling.TaskQueue;
import net.nightwhistler.pageturner.view.BookCaseView;
import net.nightwhistler.pageturner.view.FastBitmapDrawable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Text;

import roboguice.inject.InjectView;

import java.io.File;
import java.io.IOException;
import java.text.DateFormat;
import java.util.*;

import static java.lang.Character.toUpperCase;
import static java.nio.channels.Channels.*;
import static jedi.functional.FunctionalPrimitives.isEmpty;
import static jedi.option.Options.none;
import static jedi.option.Options.option;
import static jedi.option.Options.some;
import static net.nightwhistler.ui.UiUtils.onCollapse;
import static net.nightwhistler.ui.UiUtils.onMenuPress;
import android.os.Environment;
import static net.nightwhistler.pageturner.PlatformUtil.isIntentAvailable;

import java.io.FileOutputStream;
import java.io.OutputStream;

public class LibraryFragment extends RoboSherlockFragment implements ImportCallback {

    protected static final int REQUEST_CODE_GET_CONTENT = 2;
	
	@Inject 
	private LibraryService libraryService;

    @Inject
    private DialogFactory dialogFactory;
	
	@InjectView(R.id.libraryList)
	private ListView listView;
	
	@InjectView(R.id.bookCaseView)
	private BookCaseView bookCaseView;
		
	@InjectView(R.id.alphabetList)
	private ListView alphabetBar;
	
	private AlphabetAdapter alphabetAdapter;
	
	@InjectView(R.id.alphabetDivider)
	private ImageView alphabetDivider;
	
	@InjectView(R.id.libHolder)
	private ViewSwitcher switcher;

    @Inject
    private Context context;
	
	@Inject
	private Configuration config;

    @Inject
    private TaskQueue taskQueue;

	private Drawable backupCover;
	private Handler handler;
		
	private KeyedResultAdapter bookAdapter;
		
	private static final DateFormat DATE_FORMAT = DateFormat.getDateInstance(DateFormat.LONG);
	private static final int ALPHABET_THRESHOLD = 20;
	
	private ProgressDialog waitDialog;
	private ProgressDialog importDialog;	
	
	private AlertDialog importQuestion;
	
	private boolean askedUserToImport;
	private boolean oldKeepScreenOn;
	
	private static final Logger LOG = LoggerFactory.getLogger("LibraryActivity");

	private IntentCallBack intentCallBack;
	private List<CoverCallback> callbacks = new ArrayList<>();
	private Map<String, FastBitmapDrawable> coverCache = new HashMap<>();

    private MenuItem searchMenuItem;

	private interface IntentCallBack {
		void onResult( int resultCode, Intent data );
	}
	
	@Override
	public void onCreate(Bundle savedInstanceState) {			
		super.onCreate(savedInstanceState);

        LOG.debug("onCreate()");
		
		Bitmap backupBitmap = BitmapFactory.decodeResource(getResources(), R.drawable.unknown_cover );
		this.backupCover = new FastBitmapDrawable(backupBitmap);
		
		this.handler = new Handler();
				
		if ( savedInstanceState != null ) {
			this.askedUserToImport = savedInstanceState.getBoolean("import_q", false);
		}

        this.taskQueue.setTaskQueueListener(this::onTaskQueueEmpty);
	}

	@Override
	public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
		return inflater.inflate(R.layout.fragment_library, container, false);
	}

	@Override
	public void onViewCreated(View view, Bundle savedInstanceState) {
		super.onViewCreated(view, savedInstanceState);
		setHasOptionsMenu(true);
		this.bookCaseView.setOnScrollListener( new CoverScrollListener() );
		this.listView.setOnScrollListener( new CoverScrollListener() );
		
		if ( config.getLibraryView() == LibraryView.BOOKCASE ) {
			
			this.bookAdapter = new BookCaseAdapter();
			this.bookCaseView.setAdapter(bookAdapter);			
			
			if ( switcher.getDisplayedChild() == 0 ) {
				switcher.showNext();
			}
		} else {		
			this.bookAdapter = new BookListAdapter(context);
			this.listView.setAdapter(bookAdapter);
		}

		this.waitDialog = new ProgressDialog(context);
		this.waitDialog.setOwnerActivity(getActivity());
		
		this.importDialog = new ProgressDialog(context);
		
		this.importDialog.setOwnerActivity(getActivity());
		importDialog.setTitle(R.string.importing_books);
		importDialog.setMessage(getString(R.string.scanning_epub));
		registerForContextMenu(this.listView);	

        this.listView.setOnItemClickListener( this::onItemClick );
		this.listView.setOnItemLongClickListener(this::onItemLongClick );
		
		setAlphabetBarVisible(false);
	}

	@Override
	public void onActivityCreated(Bundle savedInstanceState) {
		super.onActivityCreated(savedInstanceState);
		ActionBar actionBar = getSherlockActivity().getSupportActionBar();
		actionBar.setDisplayShowTitleEnabled(false);
		actionBar.setNavigationMode(ActionBar.NAVIGATION_MODE_LIST);
		ArrayAdapter<String> adapter = new ArrayAdapter<>(actionBar.getThemedContext(),
				android.R.layout.simple_list_item_1,
				android.R.id.text1, getResources().getStringArray(R.array.libraryQueries));

		actionBar.setListNavigationCallbacks(adapter, this::onNavigationItemSelected );

        refreshView();

        LOG.debug("CARGANDO LIBRERIA "+config.getLibraryFolder());
		Option<File> libraryFolder = config.getLibraryFolder();
        String mensaje = Environment.getDataDirectory().getAbsolutePath();

        LOG.debug("DIRECTORIO => "+mensaje + " => "+ Environment.getRootDirectory().getAbsolutePath());
		libraryFolder.match(folder -> {
            LOG.error("ACA "+folder.getAbsolutePath());
            if(!folder.exists()){
                LOG.debug(" NO EXISTE");
                folder.mkdirs();
                folder.mkdir();
            }
            else{
                LOG.debug(" EXISTE");
            }

            if (folder.isDirectory()) {
                LOG.debug("ES DIRECTORIO");
            }
            executeTask(new CleanFilesTask(libraryService, this::booksDeleted));
            ImportTask task = new ImportTask(getActivity(), libraryService, this, config, config.isCopyToLibrayEnabled(), true);
            executeTask(task, folder);
            //if(task.emptyLibrary==true){
            if(libraryService.findAllByTitle(null).getSize() == 0){
                LOG.debug("VACIO copia los ficheros");



                try {
                    ParseQuery<ParseObject> query = new ParseQuery<ParseObject>(
                            "AppBook");
                    query.include("book");
                 //   query.whereEqualTo("playerName", getString(R.string.id_book));
                    query.orderByDescending("_created_at");
                    List<ParseObject> ob = query.find();
                    LOG.debug("ejecutar parse");
                    //				for (ParseObject country : ob) {
                    for(ParseObject book:ob){
                        ParseObject books=book.getParseObject("book");
                        ParseFile image =(ParseFile) books.get("cover");
                        ParseFile epub =(ParseFile) books.get("container");
                        try{
                        LOG.debug("parse victor "+epub.getUrl());

                        InputStream in = new ByteArrayInputStream(epub.getData()); //"epub/"+
                            // LOG.debug("Se puede escribir  "+in.read());
                            String tituloId= (String)books.get("eISBN");
                            File outFile = new File(""+folder.getAbsolutePath() +"/"+tituloId);
                            LOG.debug(" Name => "+tituloId + " => Se puede Escribir      "+outFile.canWrite());
                            if(!outFile.exists()) {
                                outFile.createNewFile();
                            }

                            writeToFile(in, ""+folder.getAbsolutePath() +"/"+tituloId);
                            if(image!=null) {

                                LOG.debug("parse victor if "+epub.getUrl());
                                /*WorldPopulation map= new WorldPopulation();
                                map.setTitle((String)books.get("title"));
                                map.setAuthor((String)books.get("author"));
                                map.setDescription((String)books.get("description"));
                                map.setContainer(epub.getUrl());
                                map.setCover(image.getUrl());
                                worldpopulationlist.add(map);*/
                            }else{
                            }

                            } catch (IOException e) {
                                e.printStackTrace();
                            }

                    }

                    //ob = query.find();
                } catch (ParseException e) {
                    e.printStackTrace();
                }

                /**
                 *  INSERTANDO ARCHIVOS
                 */
                /***
                 ***
                File folderToScan;
                AssetManager am = context.getAssets();
                try {
                    // InputStream is =am.open("beep.mp3");
                    folder.exists();
                    String[] files = am.list("epub");
                    for(int i=0; i<files.length; i++)
                    {
                        LOG.debug("\n File :"+i+" Name => "+files[i]);

                        InputStream in = am.open("epub/"+files[i]); //"epub/"+
                       // LOG.debug("Se puede escribir  "+in.read());
                        File outFile = new File(""+folder.getAbsolutePath() +"/"+files[i]);
                        LOG.debug(" Name => "+files[i] + " => Se puede Escribir      "+outFile.canWrite());
                        if(!outFile.exists()) {
                            outFile.createNewFile();
                        }

                        writeToFile(in, ""+folder.getAbsolutePath() +"/"+files[i]);
                       


                        LOG.debug(" Name => "+files[i] + " => Tamaño    "+outFile.length());
                        LOG.debug(" Name => "+files[i] + " => Es un fichero    "+outFile.isFile());
                        LOG.debug(" Name => "+files[i] + " => Se puede leer    "+outFile.canRead());
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                }
                //folderToScan = new File(""+config.getLibraryFolder());
                // startImport(folderToScan, true);
                LOG.debug("FINALIZA COPIA");
                ***
                ***/
                startImport(folder, true);
                /**
                 *
                 */
            }
            else{
                LOG.debug("NO INGRESA ELSE");
            }
            LOG.error("FINALIZA ACA");
        }, () -> {
            LOG.error("No library folder present!");

            Toast.makeText(context, R.string.library_failed, Toast.LENGTH_LONG).show();
        });

	}

    private void writeToFile(InputStream stream, String filePath) throws IOException {
        FileChannel outChannel = new FileOutputStream(filePath).getChannel();
        ReadableByteChannel inChannel = Channels.newChannel(stream);
        ByteBuffer buffer = ByteBuffer.allocate(1024);

        while(true) {
            if(inChannel.read(buffer) == -1) {
                break;
            }

            buffer.flip();
            outChannel.write(buffer);
            buffer.clear();
        }

        inChannel.close();
        outChannel.close();
    }
    private <A,B,C> void executeTask( QueueableAsyncTask<A,B,C> task, A... parameters ) {
        setSupportProgressBarIndeterminateVisibility(true);
        this.taskQueue.executeTask(task, parameters);
    }

    /**
     * Triggered by the TaskQueue when all tasks are finished.
     */
    private void onTaskQueueEmpty() {
        LOG.debug( "Got onTaskQueueEmpty()" );
        setSupportProgressBarIndeterminateVisibility(false);
    }

    private void clearCoverCache() {
		for ( Map.Entry<String, FastBitmapDrawable> draw: coverCache.entrySet() ) {
			draw.getValue().destroy();
		}

		coverCache.clear();
	}

	private void onItemClick(AdapterView<?> parent, View view, int position,
			long id) {

        if ( config.getLongShortPressBehaviour() == Configuration.LongShortPressBehaviour.NORMAL ) {
            this.bookAdapter.getResultAt( position ).forEach( this::showBookDetails );
        } else {
            this.bookAdapter.getResultAt( position ).forEach( this::openBook );
        }
	}	

	private boolean onItemLongClick(AdapterView<?> parent, View view,
			int position, long id) {

        if ( config.getLongShortPressBehaviour() == Configuration.LongShortPressBehaviour.NORMAL ) {
            this.bookAdapter.getResultAt( position ).forEach(this::openBook);
        } else {
            this.bookAdapter.getResultAt( position ).forEach( this::showBookDetails );
        }

		return true;
	}


	private Option<Drawable> getCover( LibraryBook book ) {

        try {
            //if ( !coverCache.containsKey(book.getFileName() ) ) {
            /*
            LOG.debug("=> RUTA IMAGEN COVER "+book.getFileName());
            String[] id =book.getFileName().split("/");
            int valor = id.length -1 ;
            LOG.debug("==>ID ruta fichero COVER " + id[valor]);
            String imagen= id[valor].replace(".epub","");*/

            /*
            //FOTO CON JGP
            String imagen = book.getIdBook();
            String rutaImagen = (""+config.getLibraryFolder()).replace("Some: ","") +"/"+imagen+".jpg";
            //Drawable draw = coverCache.get(book.getFileName());
            LOG.debug("==>ID ruta fichero ruta imagen COVER" + rutaImagen);

            File imageFile = new File(rutaImagen);
            LOG.debug("==>EXISTE  imagen COVER => " + imageFile.exists());
            Bitmap bitmap = BitmapFactory.decodeFile(imageFile.getAbsolutePath());
            */
           // Bitmap bitmapAux = BitmapFactory.decodeFile(imageFile.getAbsolutePath());
            //ByteArrayOutputStream bos = new ByteArrayOutputStream();
           // bitmapAux.compress(Bitmap.CompressFormat.PNG, 0 /*ignored for PNG*/, bos);
           // bitmapAux.recycle();
            //Bitmap bitmap = BitmapFactory.decodeByteArray(bos.toByteArray(), 0, bos.toByteArray().length );

            //FOTO ANTERIOR
                Bitmap bitmap = BitmapFactory.decodeByteArray(book.getCoverImage(), 0, book.getCoverImage().length );
                FastBitmapDrawable drawable = new FastBitmapDrawable(bitmap);

                coverCache.put( book.getFileName(), drawable );


            //}

            return option(coverCache.get(book.getFileName()));

        } catch ( OutOfMemoryError outOfMemoryError ) {
            clearCoverCache();
            return none();
        }
	}
	
	private void showBookDetails( final LibraryBook libraryBook ) {

        if ( ! isAdded() || libraryBook == null ) {
            return;
        }


        AlertDialog.Builder builder = new AlertDialog.Builder(getActivity());
		builder.setTitle(R.string.book_details);
		LayoutInflater inflater = PlatformUtil.getLayoutInflater(getActivity());
		
		View layout = inflater.inflate(R.layout.book_details, null);
		builder.setView( layout );
		
		ImageView coverView = (ImageView) layout.findViewById(R.id.coverImage );

		if ( libraryBook.getCoverImage() != null ) {
            Drawable coverDrawable = getCover(libraryBook).getOrElse(
                    getResources().getDrawable(R.drawable.unknown_cover) );

            coverView.setImageDrawable(coverDrawable);
        }

		TextView titleView = (TextView) layout.findViewById(R.id.titleField);
		TextView authorView = (TextView) layout.findViewById(R.id.authorField);
		TextView lastRead = (TextView) layout.findViewById(R.id.lastRead);
		TextView added = (TextView) layout.findViewById(R.id.addedToLibrary);
		TextView descriptionView = (TextView) layout.findViewById(R.id.bookDescription);
		// DETALLE LIBRO MODIFICADO POR VICTOR
		//TextView fileName = (TextView) layout.findViewById(R.id.fileName);
		
		titleView.setText(libraryBook.getTitle());
		String authorText = String.format( getString(R.string.book_by),
				 libraryBook.getAuthor().getFirstName() + " " 
				 + libraryBook.getAuthor().getLastName() );
		authorView.setText( authorText );
		//fileName.setText( libraryBook.getFileName() );
        //detalle de libro
        String detalleLibro = "";
        String json=openJson("meta");
        //LOG.debug("00Javier===>");

        detalleLibro = buscarInformacion(json,libraryBook.getIdBook());


		if (libraryBook.getLastRead() != null && ! libraryBook.getLastRead().equals(new Date(0))) {
			String lastReadText = String.format(getString(R.string.last_read),
					DATE_FORMAT.format(libraryBook.getLastRead()));
			lastRead.setText( lastReadText );
		} else {
			String lastReadText = String.format(getString(R.string.last_read), getString(R.string.never_read));
			lastRead.setText( lastReadText );
		}

		String addedText = String.format( getString(R.string.added_to_lib),
				DATE_FORMAT.format(libraryBook.getAddedToLibrary()));
		added.setText( addedText );

        HtmlSpanner spanner = new HtmlSpanner();
        spanner.unregisterHandler("img" ); //We don't want to render images
        if(!"".equals(detalleLibro)){
            descriptionView.setText(spanner.fromHtml( detalleLibro));
            LOG.debug("DETALLE LIBRO => "+detalleLibro);
        }
        else{
            descriptionView.setText(spanner.fromHtml( libraryBook.getDescription()));
        }


        /*builder.setNeutralButton(R.string.delete, (dialog, which) -> {
            libraryService.deleteBook( libraryBook.getFileName() );
            refreshView();
            dialog.dismiss();
        }
        */
        //);
		
		builder.setNegativeButton(android.R.string.cancel, null);
		builder.setPositiveButton(R.string.read, (dialog, which) -> openBook(libraryBook) );

		builder.show();
	}

    protected String  buscarInformacion(String jsonStr, String textoDocumento) {
        String salida="";
        try{
            JSONArray jArray=new JSONArray(jsonStr);
            for(int i=0;i<jArray.length();i++){
                JSONObject json_data=jArray.getJSONObject(i);
                if(json_data.getString("eISBNClean").equals(textoDocumento)){
                    salida=json_data.getString("Descripcion");
                }
            }
        }catch (JSONException e){
            //LOG.debug("JAVIER====>");
        }


        return salida;
    }


	private void openBook(LibraryBook libraryBook) {
		Intent intent = new Intent(getActivity(), ReadingActivity.class);
        config.setLastActivity( ReadingActivity.class );

		intent.setData( Uri.parse(libraryBook.getFileName()));
		getActivity().setResult(Activity.RESULT_OK, intent);
				
		getActivity().startActivityIfNeeded(intent, 99);		
	}
		
	private void startImport(File startFolder, boolean copy) {		
		ImportTask importTask = new ImportTask(context, libraryService, this, config, copy, false);
		importDialog.setOnCancelListener(importTask);
		importDialog.show();		
				
		this.oldKeepScreenOn = listView.getKeepScreenOn();
		listView.setKeepScreenOn(true);

        this.taskQueue.clear();
        executeTask(importTask, startFolder);
	}

    @Override
	public void onActivityResult(int requestCode, int resultCode, Intent data) {
		if ( this.intentCallBack != null ) {
			this.intentCallBack.onResult(resultCode, data);
		}
	}
	
	@Override
	public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {		
        inflater.inflate(R.menu.library_menu, menu);

        UiUtils.Action toggleListener = () -> {

            if ( switcher.getDisplayedChild() == 0 ) {
                bookAdapter = new BookCaseAdapter();
                bookCaseView.setAdapter(bookAdapter);
                config.setLibraryView(LibraryView.BOOKCASE);
            } else {
                bookAdapter = new BookListAdapter(getActivity());
                listView.setAdapter(bookAdapter);
                config.setLibraryView(LibraryView.LIST);
            }

            switcher.showNext();
            refreshView();
        };

        onMenuPress( menu, R.id.shelves_view ).thenDo( toggleListener );
        //onMenuPress( menu, R.id.list_view ).thenDo( toggleListener );
        //OCULTAR BOTOM AGREGAR ARCHIVO
        //onMenuPress( menu, R.id.scan_books ).thenDo( this::showImportDialog );
        onMenuPress( menu, R.id.about ).thenDo( dialogFactory.buildAboutDialog()::show );

        onMenuPress( menu, R.id.profile_day ).thenDo(() -> switchToColourProfile(ColourProfile.DAY) );
        onMenuPress( menu, R.id.profile_night ).thenDo(() -> switchToColourProfile(ColourProfile.NIGHT) );

        this.searchMenuItem = menu.findItem(R.id.menu_search);

        if (searchMenuItem != null) {
            final SearchView searchView = (SearchView) searchMenuItem.getActionView();

            if (searchView != null) {

                searchView.setOnQueryTextListener( UiUtils.onQuery( this::performSearch ));
                searchMenuItem.setOnActionExpandListener( onCollapse(() -> performSearch("")));

            } else {
                searchMenuItem.setOnMenuItemClickListener( item -> {
                    dialogFactory.showSearchDialog(R.string.search_library, R.string.enter_query, this::performSearch);
                    return false;
                });
            }
        }

        // Only show open file item if we have a file manager installed
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("file/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
/*
        if (isIntentAvailable(getActivity(), intent)) {
            onMenuPress( menu, R.id.open_file ).thenDo( this::launchFileManager );
        } else {
            menu.findItem(R.id.open_file).setVisible(false);
        }
*/
	}

    private void launchFileManager() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("file/*");

        intent.addCategory(Intent.CATEGORY_OPENABLE);

        this.intentCallBack = (int resultCode, Intent data)  -> {
            if ( resultCode == Activity.RESULT_OK && data != null ) {
                Intent readingIntent = new Intent( getActivity(), ReadingActivity.class);
                readingIntent.setData(data.getData());
                getActivity().setResult(Activity.RESULT_OK, readingIntent);

                getActivity().startActivityIfNeeded(readingIntent, 99);
            }
        };

        try {
            startActivityForResult(intent, REQUEST_CODE_GET_CONTENT);
        } catch (ActivityNotFoundException e) {
            // No compatible file manager was found.
            Toast.makeText(getActivity(), getString(R.string.install_oi),
                    Toast.LENGTH_SHORT).show();
        }
    }

    public void onSearchRequested() {
        if ( this.searchMenuItem != null && searchMenuItem.getActionView() != null ) {
            this.searchMenuItem.expandActionView();
            this.searchMenuItem.getActionView().requestFocus();
        } else {
            dialogFactory.showSearchDialog(R.string.search_library, R.string.enter_query, this::performSearch);
        }
    }

    private void performSearch(String query) {
        if ( query != null ) {
            setSupportProgressBarIndeterminateVisibility(true);
            this.taskQueue.jumpQueueExecuteTask(new LoadBooksTask(config.getLastLibraryQuery(), query));
        }
    }
	
	private void switchToColourProfile( ColourProfile profile ) {
		config.setColourProfile(profile);
		Intent intent = new Intent(getActivity(), LibraryActivity.class);
		startActivity(intent);
		onStop();
		getActivity().finish();
	}
	
	@Override
	public void onPrepareOptionsMenu(Menu menu) {
		boolean bookCaseActive = config.getLibraryView() == LibraryView.BOOKCASE;
		
		menu.findItem(R.id.shelves_view).setVisible(! bookCaseActive);
		//menu.findItem(R.id.list_view).setVisible(bookCaseActive);
		menu.findItem(R.id.profile_day).setVisible(config.getColourProfile() == ColourProfile.NIGHT);
		menu.findItem(R.id.profile_night).setVisible(config.getColourProfile() == ColourProfile.DAY);
	}
	
	private void showImportDialog() {
		AlertDialog.Builder builder;		
		
		LayoutInflater inflater = PlatformUtil.getLayoutInflater(getActivity());
		final View layout = inflater.inflate(R.layout.import_dialog, null);
		final RadioButton scanSpecific = (RadioButton) layout.findViewById(R.id.radioScanFolder);
		final TextView folder = (TextView) layout.findViewById(R.id.folderToScan);
		final CheckBox copyToLibrary = (CheckBox) layout.findViewById(R.id.copyToLib);		
		final Button browseButton = (Button) layout.findViewById(R.id.browseButton);

		Option<File> storageBase = config.getStorageBase();

		if ( isEmpty(storageBase) ) {
			return;
		}

		File file = storageBase.unsafeGet();

		folder.setText( file.getAbsolutePath() + "/eBooks" );
		folder.setOnClickListener( v ->	scanSpecific.setChecked(true) );

		//Copy default setting from the prefs
		copyToLibrary.setChecked( config.isCopyToLibrayEnabled() );
		
		builder = new AlertDialog.Builder(getActivity());
		builder.setView(layout);

        this.intentCallBack = (int resultCode, Intent data) -> {
            if ( resultCode == Activity.RESULT_OK && data != null ) {
                folder.setText(data.getData().getPath());
            }
        };
		
		browseButton.setOnClickListener(v -> {
            scanSpecific.setChecked(true);
            Intent intent = new Intent(getActivity(), FileBrowseActivity.class);
            intent.setData( Uri.parse(folder.getText().toString() ));
            startActivityForResult(intent, 0);
        });
		
		builder.setTitle(R.string.import_books);

        builder.setPositiveButton(android.R.string.ok, (dialog, which) -> {
            dialog.dismiss();

            File folderToScan;

            if ( scanSpecific.isChecked() ) {
                folderToScan = new File(folder.getText().toString());
            } else {
                folderToScan = new File(file.getAbsolutePath());
            }

            startImport(folderToScan, copyToLibrary.isChecked());
        });

		builder.setNegativeButton(android.R.string.cancel, null);
		
		builder.show();
	}	
	
	@Override
	public void onSaveInstanceState(Bundle outState) {
		outState.putBoolean("import_q", askedUserToImport);
	}
	
	@Override
	public void onStop() {
		this.libraryService.close();	
		this.waitDialog.dismiss();
		this.importDialog.dismiss();
		super.onStop();
	}
	
	public void onBackPressed() {
		getActivity().finish();			
	}	
	
	@Override
	public void onPause() {
		
		this.bookAdapter.clear();
		//We clear the list to free up memory.

        this.taskQueue.clear();
		this.clearCoverCache();
		
		super.onPause();
	}
	
	
	@Override
	public void onResume() {
		super.onResume();				
		
		LibrarySelection lastSelection = config.getLastLibraryQuery();
		
		ActionBar actionBar = getSherlockActivity().getSupportActionBar();
		
		if (actionBar.getSelectedNavigationIndex() != lastSelection.ordinal() ) {
			actionBar.setSelectedNavigationItem(lastSelection.ordinal());
		} else {
            executeTask(new LoadBooksTask(lastSelection));
		}
	}

    @Override
    public void importCancelled(int booksImported, List<String> failures, boolean emptyLibrary, boolean silent) {
        LOG.debug("Got importCancelled() ");
        afterImport( booksImported, failures, emptyLibrary, silent, true );
    }

    @Override
	public void importComplete(int booksImported, List<String> errors, boolean emptyLibrary, boolean silent) {
        LOG.debug("Got importComplete() ");
        afterImport(booksImported, errors, emptyLibrary, silent, false);
	}

    private void afterImport(int booksImported, List<String> errors, boolean emptyLibrary, boolean silent,
                             boolean cancelledByUser ) {

        if ( !isAdded() || getActivity() == null ) {
            return;
        }

        if ( silent ) {
            if ( booksImported > 0 ) {
                //Schedule refresh without clearing the queue
                executeTask(new LoadBooksTask(config.getLastLibraryQuery()));
            }
            return;
        }

        importDialog.hide();

        //If the user cancelled the import, don't bug him/her with alerts.
        if ( (! errors.isEmpty()) ) {
            AlertDialog.Builder builder = new AlertDialog.Builder(getActivity());
            builder.setTitle(R.string.import_errors);

            builder.setItems( errors.toArray(new String[errors.size()]), null );

            builder.setNeutralButton(android.R.string.ok, (dialog, which) -> dialog.dismiss() );

            builder.show();
        }

        listView.setKeepScreenOn(oldKeepScreenOn);

        if ( booksImported > 0 ) {

            //Switch to the "recently added" view.
            if (getSherlockActivity().getSupportActionBar().getSelectedNavigationIndex() == LibrarySelection.LAST_ADDED.ordinal() ) {
                loadView(LibrarySelection.LAST_ADDED, "importComplete()");
            } else {
                getSherlockActivity().getSupportActionBar().setSelectedNavigationItem(LibrarySelection.LAST_ADDED.ordinal());
            }
        } else if ( ! cancelledByUser ) {

            AlertDialog.Builder builder = new AlertDialog.Builder(getActivity());
            builder.setTitle(R.string.no_books_found);

            if ( emptyLibrary ) {

                builder.setMessage( getString(R.string.no_bks_fnd_text2) );

                builder.setPositiveButton( android.R.string.yes, (dialogInterface, i) ->
                    ( (PageTurnerActivity) getSherlockActivity() ).launchActivity( CatalogActivity.class ));

                builder.setNegativeButton( android.R.string.no, null );

            } else {
                builder.setMessage( getString(R.string.no_new_books_found));
                builder.setNeutralButton(android.R.string.ok, ( dialog, which) -> dialog.dismiss() );
            }

            builder.show();

        }

    }
	
	
	@Override
	public void importFailed(String reason, boolean silent) {

        LOG.debug("Got importFailed()");
		
		if (silent || !isAdded() || getActivity() == null ) {
			return;
		}
		
		importDialog.hide();
		AlertDialog.Builder builder = new AlertDialog.Builder(getActivity());
		builder.setTitle(R.string.import_failed);
		builder.setMessage(reason);
		builder.setNeutralButton(android.R.string.ok, null);
		builder.show();
	}
	
	@Override
	public void importStatusUpdate(String update, boolean silent) {
		
		if (silent || !isAdded() || getActivity() == null ) {
			return;
		}
		
		importDialog.setMessage(update);
	}	
	
	public void onAlphabetBarClick( KeyedQueryResult<LibraryBook> result, Character c ) {

		result.getOffsetFor(toUpperCase(c)).forEach( index -> {
			if ( alphabetAdapter != null ) {
				alphabetAdapter.setHighlightChar(c);
			}

			if ( config.getLibraryView() == LibraryView.BOOKCASE ) {
				this.bookCaseView.setSelection(index);
			} else {
				this.listView.setSelection(index);
			}
		});
	}	
	
	
	/**
	 * Based on example found here:
	 * http://www.vogella.de/articles/AndroidListView/article.html
	 * 
	 * @author work
	 *
	 */
	private class BookListAdapter extends KeyedResultAdapter {	
		
		private Context context;		
		
		public BookListAdapter(Context context) {
			this.context = context;
		}		
		
		@Override
		public View getView(int index, final LibraryBook book, View convertView,
				ViewGroup parent) {
			
			View rowView;
			
			if ( convertView == null ) {			
				LayoutInflater inflater = (LayoutInflater) context.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
				rowView = inflater.inflate(R.layout.book_row, parent, false);
			} else {
				rowView = convertView;
			}			
			LOG.debug("VEr VISTA PREVIA BOOK");
			TextView titleView = (TextView) rowView.findViewById(R.id.bookTitle);
			TextView authorView = (TextView) rowView.findViewById(R.id.bookAuthor);
			TextView dateView = (TextView) rowView.findViewById(R.id.addedToLibrary);
			TextView progressView = (TextView) rowView.findViewById(R.id.readingProgress);
			
			final ImageView imageView = (ImageView) rowView.findViewById(R.id.bookCover);
						
			String authorText = String.format(getString(R.string.book_by),
					book.getAuthor().getFirstName() + " " + book.getAuthor().getLastName() );
			
			authorView.setText(authorText);
			titleView.setText(book.getTitle());
			
			if ( book.getProgress() > 0 ) {
				progressView.setText( "" + book.getProgress() + "%");
			} else {
				progressView.setText("");
			}			
			
			String dateText = String.format(getString(R.string.added_to_lib),
					DATE_FORMAT.format(book.getAddedToLibrary()));
			dateView.setText( dateText );
			
			loadCover(imageView, book, index);			
			
			return rowView;
		}	
	
	}

    private void loadView( LibrarySelection selection, String from ) {
        LOG.debug("Loading view: " + selection + " from " + from);
        this.taskQueue.clear();
        executeTask(new LoadBooksTask(selection));
    }

    private void refreshView() {
        LOG.debug("View refresh requested");
        loadView(config.getLastLibraryQuery(), "refreshView()");
    }

    /**
     * Called after books have been deleted.
     * @param numberOfDeletedBooks
     */
    private void booksDeleted(int numberOfDeletedBooks) {

        LOG.debug("Got " + numberOfDeletedBooks + " deleted books.");

        if ( numberOfDeletedBooks > 0 ) {

            //Schedule a refresh without clearing the task queue
            executeTask(new LoadBooksTask(config.getLastLibraryQuery()));
        }
    }

    private void loadCover( ImageView imageView, LibraryBook book, int index ) {
        /*
        LOG.debug("=> RUTA IMAGEN "+book.getFileName());
        String[] id =book.getFileName().split("/");
        int valor = id.length -1 ;
        LOG.debug("==>ID ruta fichero " + id[valor]);
        String imagen= id[valor].replace(".epub","");
        */

        ////String imagen = book.getIdBook();
        ////String rutaImagen = (""+config.getLibraryFolder()).replace("Some: ","") +"/"+imagen+".jpg";
		Drawable draw = coverCache.get(book.getFileName());
      ////  LOG.debug("==>ID ruta fichero ruta imagen" + rutaImagen);
        ////Drawable draw = coverCache.get(rutaImagen);
		
		if ( draw != null ) {
          //// LOG.debug("==>ID ruta fichero ruta imagen LO HIZO" + rutaImagen);

			imageView.setImageDrawable(draw);
		} else {
            ////File imageFile = new File(rutaImagen);
            ////Bitmap bitmap = BitmapFactory.decodeFile(imageFile.getAbsolutePath());

            ////ByteArrayOutputStream bos = new ByteArrayOutputStream();
            ////bitmap.compress(Bitmap.CompressFormat.PNG, 0 /*ignored for PNG*/, bos);
            ////bitmap.recycle();

            ////imageView.setImageBitmap(bitmap);
			imageView.setImageDrawable(backupCover);
			
			if ( book.getCoverImage() != null ) {
				callbacks.add( new CoverCallback(book, index, imageView ) );
			}
		}
	}	
	
	private class CoverScrollListener implements AbsListView.OnScrollListener {
		
		private Runnable lastRunnable;
		private Character lastCharacter;

        private Drawable holoDrawable;

        public  CoverScrollListener() {
            try {
                this.holoDrawable = getResources().getDrawable(R.drawable.list_activated_holo);
            } catch (IllegalStateException i) {
                //leave it null
            }
        }
		
		@Override
		public void onScroll(AbsListView view, final int firstVisibleItem,
				final int visibleItemCount, final int totalItemCount) {
			
			if ( visibleItemCount == 0  ) {
				return;
			}
			
			if ( this.lastRunnable != null ) {
				handler.removeCallbacks(lastRunnable);
			}

            this.lastRunnable = () -> {

                if ( bookAdapter.isKeyed() ) {

                    String key = bookAdapter.getKey(firstVisibleItem).getOrElse("");

                    if (key.length() > 0) {
                        Character keyChar = toUpperCase(key.charAt(0));

                        if (keyChar.equals(lastCharacter)) {

                            lastCharacter = keyChar;
                            List<Character> alphabet = bookAdapter.getAlphabet();

                            //If the highlight-char is already set, this means the
                            //user clicked the bar, so don't scroll it.
                            if (alphabetAdapter != null && !keyChar.equals(alphabetAdapter.getHighlightChar())) {
                                alphabetAdapter.setHighlightChar(keyChar);
                                alphabetBar.setSelection(alphabet.indexOf(keyChar));
                            }

                            for (int i = 0; i < alphabetBar.getChildCount(); i++) {
                                View child = alphabetBar.getChildAt(i);
                                if (child.getTag().equals(keyChar)) {
                                    child.setBackgroundDrawable(holoDrawable);
                                } else {
                                    child.setBackgroundDrawable(null);
                                }
                            }
                        }
                    }
                }

                List<CoverCallback> localList = new ArrayList<>( callbacks );
                callbacks.clear();

                int lastVisibleItem = firstVisibleItem + visibleItemCount - 1;

                LOG.debug( "Loading items " + firstVisibleItem + " to " + lastVisibleItem + " of " + totalItemCount );

                for ( CoverCallback callback: localList ) {
                    if ( callback.viewIndex >= firstVisibleItem && callback.viewIndex <= lastVisibleItem ) {
                        callback.run();
                    }
                }

            };
				
			handler.postDelayed(lastRunnable, 550);			
		}
		
		@Override
		public void onScrollStateChanged(AbsListView view, int scrollState) {
					
		}

	}

	private class CoverCallback {
		protected LibraryBook book;
		protected int viewIndex;
		protected ImageView view;

		public CoverCallback(LibraryBook book, int viewIndex, ImageView view) {
			this.book = book;
			this.view = view;
			this.viewIndex = viewIndex;
		}

		public void run() {
			try {
                getCover(book).forEach( view::setImageDrawable );
            } catch (IllegalStateException i) {
                //Do nothing, happens when we're no longer attached.
            }
		}
	}


	private class BookCaseAdapter extends KeyedResultAdapter {
				
		@Override
		public View getView(final int index, final LibraryBook object, View convertView,
				ViewGroup parent) {
			
			View result;
		
			if ( convertView == null ) {				
				LayoutInflater inflater = PlatformUtil.getLayoutInflater(getActivity());
				result = inflater.inflate(R.layout.bookcase_row, parent, false);
				
			} else {
				result = convertView;
			}			
			
			result.setTag(index);
			
			result.setOnClickListener( v -> LibraryFragment.this.onItemClick(null, null, index, 0) );
			result.setOnLongClickListener( v -> LibraryFragment.this.onItemLongClick(null, null, index, 0));
			
			final ImageView image = (ImageView) result.findViewById(R.id.bookCover);
            /*
            LOG.debug("=> RUTA IMAGEN2 "+object.getFileName());
            String[] id =object.getFileName().split("/");
            int valor = id.length -1 ;
            LOG.debug("==>ID ruta fichero2 " + id[valor]);
            String imagen= id[valor].replace(".epub","");
            */

            /*

            //FOTO CON JGP
            String imagen = object.getIdBook();
            String rutaImagen = (""+config.getLibraryFolder()).replace("Some: ","") +"/"+imagen+".jpg";
            //Drawable draw = coverCache.get(book.getFileName());
            LOG.debug("==>ID ruta fichero ruta imagen2" + rutaImagen);

            File imageFile = new File(rutaImagen);
            Bitmap bitmap = BitmapFactory.decodeFile(imageFile.getAbsolutePath());
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.PNG, 0, bos);
            bitmap.recycle();
            image.setImageBitmap(bitmap);*/

			//image.setImageDrawable(backupCover);
        //  MODIFICADO POR VICTOR
		//	TextView text = (TextView) result.findViewById(R.id.bookLabel);
		//	text.setText( object.getTitle() );
		//	text.setBackgroundResource(R.drawable.alphabet_bar_bg_dark);
			
			loadCover(image, object, index);		
			
			return result;
		}
		
	}
	
	private void buildImportQuestionDialog() {
		
		if ( importQuestion != null || ! isAdded() ) {
			return;
		}
		
		AlertDialog.Builder builder = new AlertDialog.Builder(getActivity());
		builder.setTitle(R.string.no_books_found);
		builder.setMessage( getString(R.string.scan_bks_question) );

        builder.setPositiveButton(android.R.string.yes, (dialog, which) -> {
            dialog.dismiss();
            showImportDialog();
        });

        builder.setNegativeButton(android.R.string.no, (dialog, which ) -> {
            dialog.dismiss();
            importQuestion = null;
        });

        this.importQuestion = builder.create();
	}
	
	private void setAlphabetBarVisible( boolean visible ) {
		
		int vis = visible ? View.VISIBLE : View.GONE; 
		
		alphabetBar.setVisibility(vis);
		alphabetDivider.setVisibility(vis);		
		listView.setFastScrollEnabled(visible);
	}

    private void setSupportProgressBarIndeterminateVisibility(boolean enable) {
        SherlockFragmentActivity activity = getSherlockActivity();
        if ( activity != null) {
            LOG.debug("Setting progress bar to " + enable );
            activity.setSupportProgressBarIndeterminateVisibility(enable);
        } else {
            LOG.debug("Got null activity.");
        }
    }

    private boolean onNavigationItemSelected(int pos, long arg1) {

        LibrarySelection newSelections = LibrarySelection.values()[pos];

        if ( newSelections != config.getLastLibraryQuery() ) {
            config.setLastLibraryQuery(newSelections);

            bookAdapter.clear();
            loadView(newSelections, "onNavigationItemSelected()");
        }

        return false;
    }

	private class AlphabetAdapter extends ArrayAdapter<Character> {
		
		private List<Character> data;
		
		private Character highlightChar;
		
		public AlphabetAdapter(Context context, int layout, int view, List<Character> input ) {
			super(context, layout, view, input);
			this.data = input;
		}
		
		@Override
		public View getView(int position, View convertView, ViewGroup parent) {
			View view = super.getView(position, convertView, parent);
			
			Character tag = data.get(position);
			view.setTag( tag );
			
			if ( tag.equals(highlightChar) ) {
				view.setBackgroundDrawable( getResources().getDrawable(R.drawable.list_activated_holo));
			} else {
				view.setBackgroundDrawable(null);
			}
			
			return view;
		}
		
		public void setHighlightChar(Character highlightChar) {
			this.highlightChar = highlightChar;
		}
		
		public Character getHighlightChar() {
			return highlightChar;
		}
	}

    private void loadQueryData( QueryResult<LibraryBook> result ) {
        if ( !isAdded() || getActivity() == null ) {
            return;
        }

        bookAdapter.setResult(result);

        if ( result instanceof KeyedQueryResult && result.getSize() >= ALPHABET_THRESHOLD ) {

            final KeyedQueryResult<LibraryBook> keyedResult = (KeyedQueryResult<LibraryBook>) result;

            alphabetAdapter = new AlphabetAdapter(getActivity(),
                    R.layout.alphabet_line, R.id.alphabetLabel,	keyedResult.getAlphabet() );

            alphabetBar.setAdapter(alphabetAdapter);

            alphabetBar.setOnItemClickListener( (a, b, index, c) ->
                    onAlphabetBarClick(keyedResult, keyedResult.getAlphabet().get(index) ));

            setAlphabetBarVisible(true);
        } else {
            alphabetAdapter = null;
            setAlphabetBarVisible(false);
        }
    }

	private class LoadBooksTask extends QueueableAsyncTask<String, Integer, QueryResult<LibraryBook>> {
		
		private Configuration.LibrarySelection sel;
        private String filter;

        public LoadBooksTask(LibrarySelection selection) {
            this.sel = selection;
        }

        public LoadBooksTask(LibrarySelection selection, String filter ) {
            this(selection);
            this.filter = filter;
        }

        @Override
        public void doOnPreExecute() {
            if ( this.filter == null )  {
			    coverCache.clear();
            }
		}
		
		@Override
		public Option<QueryResult<LibraryBook>> doInBackground(String... params) {
			
			Exception storedException = null;

            String query = this.filter;
			
			for ( int i=0; i < 3; i++ ) {

				try {

					switch ( sel ) {			
					case LAST_ADDED:
						return some(libraryService.findAllByLastAdded(query));
					case UNREAD:
						return some(libraryService.findUnread(query));
					case BY_TITLE:
						return some(libraryService.findAllByTitle(query));
					case BY_AUTHOR:
						return some(libraryService.findAllByAuthor(query));
					default:
						return some(libraryService.findAllByLastRead(query));
					}
				} catch (SQLiteException sql) {
					storedException = sql;
					try {
						//Sometimes the database is still locked.
						Thread.sleep(1000);
					} catch (InterruptedException in) {}
				}				
			}
			
			LOG.error( "Failed after 3 attempts", storedException );
            return none();
		}

        @Override
        public void doOnPostExecute(Option<QueryResult<LibraryBook>> result) {

            result.match(r -> {

                loadQueryData(r);

                if (filter == null && sel == Configuration.LibrarySelection.LAST_ADDED && r.getSize() == 0 && !askedUserToImport) {
                    askedUserToImport = true;
                    buildImportQuestionDialog();
                    importQuestion.show();
                }
            }, () -> Toast.makeText(context, R.string.library_failed, Toast.LENGTH_SHORT).show());

		}
		
	}

    public String openJson(String name){
        //lectura de json
        AssetManager am = context.getAssets();
        String json="";
        try {
            InputStream in = am.open(name+".json");
            int size=in.available();
            byte[] buffer=new byte[size];
            in.read(buffer);
            in.close();
            json=new String(buffer, "UTF-8");
        }catch (IOException ex){
            ex.printStackTrace();
        }
       // LOG.debug("JAVIER===>"+json);
        return json;
    }
}
