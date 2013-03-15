/**
 * Copyright (c) 2012 Todoroo Inc
 *
 * See the file "LICENSE" for the full license governing this code.
 */
package com.todoroo.astrid.actfm;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.MalformedURLException;
import java.util.HashMap;
import java.util.Map.Entry;
import java.util.Random;
import java.util.Set;

import org.json.JSONException;
import org.json.JSONObject;

import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Bundle;
import android.support.v4.app.FragmentActivity;
import android.text.InputType;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.TextUtils;
import android.text.method.PasswordTransformationMethod;
import android.text.style.ClickableSpan;
import android.text.style.UnderlineSpan;
import android.util.DisplayMetrics;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup.LayoutParams;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.facebook.android.AsyncFacebookRunner;
import com.facebook.android.AsyncFacebookRunner.RequestListener;
import com.facebook.android.AuthListener;
import com.facebook.android.Facebook;
import com.facebook.android.FacebookError;
import com.facebook.android.LoginButton;
import com.facebook.android.Util;
import com.google.android.googlelogin.GoogleLoginServiceConstants;
import com.google.android.googlelogin.GoogleLoginServiceHelper;
import com.timsu.astrid.GCMIntentService;
import com.timsu.astrid.R;
import com.todoroo.andlib.data.TodorooCursor;
import com.todoroo.andlib.service.Autowired;
import com.todoroo.andlib.service.ContextManager;
import com.todoroo.andlib.service.DependencyInjectionService;
import com.todoroo.andlib.service.ExceptionService;
import com.todoroo.andlib.sql.Criterion;
import com.todoroo.andlib.sql.Query;
import com.todoroo.andlib.utility.AndroidUtilities;
import com.todoroo.andlib.utility.DateUtilities;
import com.todoroo.andlib.utility.DialogUtilities;
import com.todoroo.andlib.utility.Preferences;
import com.todoroo.astrid.actfm.sync.ActFmInvoker;
import com.todoroo.astrid.actfm.sync.ActFmPreferenceService;
import com.todoroo.astrid.actfm.sync.ActFmServiceException;
import com.todoroo.astrid.actfm.sync.ActFmSyncMonitor;
import com.todoroo.astrid.actfm.sync.messages.ConstructOutstandingTableFromMasterTable;
import com.todoroo.astrid.actfm.sync.messages.ConstructTaskOutstandingTableFromMasterTable;
import com.todoroo.astrid.actfm.sync.messages.NameMaps;
import com.todoroo.astrid.activity.Eula;
import com.todoroo.astrid.dao.Database;
import com.todoroo.astrid.dao.MetadataDao;
import com.todoroo.astrid.dao.MetadataDao.MetadataCriteria;
import com.todoroo.astrid.dao.RemoteModelDao;
import com.todoroo.astrid.dao.TagDataDao;
import com.todoroo.astrid.dao.TagMetadataDao;
import com.todoroo.astrid.dao.TagOutstandingDao;
import com.todoroo.astrid.dao.TaskAttachmentDao;
import com.todoroo.astrid.dao.TaskAttachmentOutstandingDao;
import com.todoroo.astrid.dao.TaskDao;
import com.todoroo.astrid.dao.TaskListMetadataDao;
import com.todoroo.astrid.dao.TaskListMetadataOutstandingDao;
import com.todoroo.astrid.dao.TaskOutstandingDao;
import com.todoroo.astrid.dao.UserActivityDao;
import com.todoroo.astrid.dao.UserActivityOutstandingDao;
import com.todoroo.astrid.dao.UserDao;
import com.todoroo.astrid.data.Metadata;
import com.todoroo.astrid.data.RemoteModel;
import com.todoroo.astrid.data.TagData;
import com.todoroo.astrid.data.TagOutstanding;
import com.todoroo.astrid.data.Task;
import com.todoroo.astrid.data.TaskListMetadata;
import com.todoroo.astrid.data.TaskListMetadataOutstanding;
import com.todoroo.astrid.data.UserActivity;
import com.todoroo.astrid.data.UserActivityOutstanding;
import com.todoroo.astrid.gtasks.auth.ModernAuthManager;
import com.todoroo.astrid.helper.UUIDHelper;
import com.todoroo.astrid.service.AstridDependencyInjector;
import com.todoroo.astrid.service.MarketStrategy.AmazonMarketStrategy;
import com.todoroo.astrid.service.StatisticsConstants;
import com.todoroo.astrid.service.StatisticsService;
import com.todoroo.astrid.service.SyncV2Service;
import com.todoroo.astrid.service.TaskService;
import com.todoroo.astrid.subtasks.AstridOrderedListUpdater;
import com.todoroo.astrid.subtasks.AstridOrderedListUpdater.Node;
import com.todoroo.astrid.subtasks.SubtasksHelper;
import com.todoroo.astrid.subtasks.SubtasksHelper.TreeRemapHelper;
import com.todoroo.astrid.tags.TaskToTagMetadata;

/**
 * This activity allows users to sign in or log in to Astrid.com
 *
 * @author Tim Su <tim@astrid.com>
 *
 */
public class ActFmLoginActivity extends FragmentActivity implements AuthListener {

    public static final String APP_ID = "183862944961271"; //$NON-NLS-1$

    @Autowired
    protected Database database;
    @Autowired
    protected ExceptionService exceptionService;
    @Autowired
    protected TaskService taskService;
    @Autowired
    protected ActFmPreferenceService actFmPreferenceService;

    @Autowired
    private TaskDao taskDao;
    @Autowired
    private TaskOutstandingDao taskOutstandingDao;
    @Autowired
    private TaskAttachmentDao taskAttachmentDao;
    @Autowired
    private TaskAttachmentOutstandingDao taskAttachmentOutstandingDao;
    @Autowired
    private TagDataDao tagDataDao;
    @Autowired
    private TagOutstandingDao tagOutstandingDao;
    @Autowired
    private UserDao userDao;
    @Autowired
    private UserActivityDao userActivityDao;
    @Autowired
    private UserActivityOutstandingDao userActivityOutstandingDao;
    @Autowired
    private TaskListMetadataDao taskListMetadataDao;
    @Autowired
    private TaskListMetadataOutstandingDao taskListMetadataOutstandingDao;
    @Autowired
    private MetadataDao metadataDao;
    @Autowired
    private TagMetadataDao tagMetadataDao;


    @Autowired protected SyncV2Service syncService;
    private final ActFmInvoker actFmInvoker = new ActFmInvoker();
    private Random rand;

    private Facebook facebook;
    private AsyncFacebookRunner facebookRunner;
    protected TextView errors;

    public static final String SHOW_TOAST = "show_toast"; //$NON-NLS-1$

    // --- ui initialization

    private static final int REQUEST_CODE_GOOGLE_ACCOUNTS = 1;
    private static final int REQUEST_CODE_GOOGLE = 2;

    static {
        AstridDependencyInjector.initialize();
    }

    protected int getContentViewResource() {
        return R.layout.actfm_login_activity;
    }

    protected int getTitleResource() {
        return R.string.actfm_ALA_title;
    }

    public ActFmLoginActivity() {
        super();
        DependencyInjectionService.getInstance().inject(this);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ContextManager.setContext(this);

        setContentView(getContentViewResource());
        if(getTitleResource() != 0)
            setTitle(getTitleResource());

        if (getSupportActionBar() != null)
            getSupportActionBar().hide();

        rand = new Random(DateUtilities.now());

        initializeUI();

        getWindow().setFormat(PixelFormat.RGBA_8888);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_DITHER);

        recordPageView();

        setResult(RESULT_CANCELED);
    }


    /* (non-Javadoc)
     * @see android.app.Activity#onConfigurationChanged(android.content.res.Configuration)
     */
    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        setContentView(getContentViewResource());
    }

    @Override
    protected void onResume() {
        super.onResume();
        StatisticsService.sessionStart(this);
    }

    @Override
    protected void onPause() {
        super.onPause();
        StatisticsService.sessionPause();
    }

    @Override
    protected void onStop() {
        StatisticsService.sessionStop(this);
        super.onStop();
    }

    protected void recordPageView() {
        StatisticsService.reportEvent(StatisticsConstants.ACTFM_LOGIN_SHOW);
    }

    protected void setupTermsOfService(TextView tos) {
        OnClickListener showTosListener = new OnClickListener() {
            @Override
            public void onClick(View v) {
                Eula.showEulaBasic(ActFmLoginActivity.this);
            }
        };

        tos.setOnClickListener(showTosListener);

        String tosBase = getString(R.string.welcome_login_tos_base);
        String tosLink = getString(R.string.welcome_login_tos_link);
        SpannableString link = getLinkStringWithCustomInterval(tosBase, tosLink, tosBase.length() + 1, 0,
                showTosListener);
        tos.setText(link);
    }


    protected SpannableString getLinkStringWithCustomInterval(String base, String linkComponent,
                                                            int start, int endOffset, final OnClickListener listener) {
        SpannableString link = new SpannableString (String.format("%s %s", //$NON-NLS-1$
                base, linkComponent));
        ClickableSpan linkSpan = new ClickableSpan() {
            @Override
            public void onClick(View widget) {
                listener.onClick(widget);
            }
            @Override
            public void updateDrawState(TextPaint ds) {
                ds.setUnderlineText(true);
                ds.setColor(Color.rgb(68, 68, 68));
            }
        };
        link.setSpan(linkSpan, start, link.length() + endOffset, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return link;
    }

    @SuppressWarnings("nls")
    protected void initializeUI() {
        facebook = new Facebook(APP_ID);
        facebookRunner = new AsyncFacebookRunner(facebook);

        errors = (TextView) findViewById(R.id.error);
        LoginButton loginButton = (LoginButton) findViewById(R.id.fb_login);
        if(loginButton == null)
            return;

        loginButton.init(this, facebook, this, new String[] { "email",
                "offline_access", "publish_stream" });

        View googleLogin = findViewById(R.id.gg_login);
        if(AmazonMarketStrategy.isKindleFire())
            googleLogin.setVisibility(View.GONE);
        googleLogin.setOnClickListener(googleListener);
        TextView signUp = (TextView) findViewById(R.id.pw_signup);
        signUp.setOnClickListener(signUpListener);

        TextView signIn = (TextView) findViewById(R.id.pw_login);
        signIn.setOnClickListener(signInListener);

        setupTermsOfService((TextView) findViewById(R.id.tos));
    }

    // --- event handler

    protected final OnClickListener googleListener = new OnClickListener() {
        @Override
        public void onClick(View arg0) {
            Intent intent = new Intent(ActFmLoginActivity.this,
                    ActFmGoogleAuthActivity.class);
            startActivityForResult(intent, REQUEST_CODE_GOOGLE);
            StatisticsService.reportEvent(StatisticsConstants.ACTFM_LOGIN_GL_START);
        }
    };

    protected final OnClickListener signUpListener = new OnClickListener() {
        @Override
        public void onClick(View v) {
            final LinearLayout body = new LinearLayout(ActFmLoginActivity.this);
            body.setOrientation(LinearLayout.VERTICAL);
            body.setPadding(10, 0, 10, 0);

            final EditText firstNameField = addEditField(body,
                    R.string.actfm_ALA_firstname_label);
            firstNameField.setInputType(InputType.TYPE_CLASS_TEXT |
                    InputType.TYPE_TEXT_VARIATION_PERSON_NAME |
                    InputType.TYPE_TEXT_FLAG_CAP_WORDS);

            final EditText lastNameField = addEditField(body,
                    R.string.actfm_ALA_lastname_label);
            lastNameField.setInputType(InputType.TYPE_CLASS_TEXT |
                    InputType.TYPE_TEXT_VARIATION_PERSON_NAME |
                    InputType.TYPE_TEXT_FLAG_CAP_WORDS);

            final EditText email = addEditField(body,
                    R.string.actfm_ALA_email_label);
            email.setInputType(InputType.TYPE_CLASS_TEXT |
                    InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
            getCredentials(new OnGetCredentials() {
                @Override
                public void getCredentials(String[] accounts) {
                    if (accounts != null && accounts.length > 0)
                        email.setText(accounts[0]);
                }
            });

            ScrollView bodyScroll = new ScrollView(ActFmLoginActivity.this);
            bodyScroll.addView(body);

            new AlertDialog.Builder(ActFmLoginActivity.this).setView(
                    bodyScroll).setIcon(R.drawable.icon_32).setTitle(
                    R.string.actfm_ALA_signup_title).setPositiveButton(
                    android.R.string.ok, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dlg, int which) {
                            String firstName = firstNameField.getText().toString();
                            String lastName =lastNameField.getText().toString();

                            AndroidUtilities.hideSoftInputForViews(ActFmLoginActivity.this, firstNameField, lastNameField, email);
                            authenticate(email.getText().toString(),
                                    firstName, lastName, ActFmInvoker.PROVIDER_PASSWORD, generateRandomPassword());
                            StatisticsService.reportEvent(StatisticsConstants.ACTFM_SIGNUP_PW);
                        }
                    }).setNegativeButton(android.R.string.cancel, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dlg, int which) {
                            AndroidUtilities.hideSoftInputForViews(ActFmLoginActivity.this, firstNameField, lastNameField, email);
                        }
                    }).show();
        }
    };

    protected final OnClickListener signInListener = new OnClickListener() {
        public void onClick(View v) {
            final LinearLayout body = new LinearLayout(ActFmLoginActivity.this);
            body.setOrientation(LinearLayout.VERTICAL);
            body.setPadding(10, 0, 10, 0);

            final EditText email = addEditField(body,
                    R.string.actfm_ALA_email_label);
            email.setInputType(InputType.TYPE_CLASS_TEXT |
                    InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
            getCredentials(new OnGetCredentials() {
                @Override
                public void getCredentials(String[] accounts) {
                    if (accounts != null && accounts.length > 0)
                        email.setText(accounts[0]);
                }
            });

            final EditText password = addEditField(body,
                    R.string.actfm_ALA_password_label);
            password.setInputType(InputType.TYPE_CLASS_TEXT |
                    InputType.TYPE_TEXT_VARIATION_PASSWORD);
            password.setTransformationMethod(new PasswordTransformationMethod());

            TextView forgotPassword = new TextView(ActFmLoginActivity.this);
            SpannableString text = new SpannableString(getString(R.string.actfm_ALA_forgot_password));
            text.setSpan(new UnderlineSpan(), 0, text.length(), 0);
            forgotPassword.setText(text);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
            DisplayMetrics metrics = getResources().getDisplayMetrics();
            lp.setMargins(0, (int) (8 * metrics.density), 0, (int) (8 * metrics.density));
            forgotPassword.setLayoutParams(lp);
            forgotPassword.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View view) {
                    forgotPassword(email.getText().toString());
                }
            });
            body.addView(forgotPassword);


            ScrollView bodyScroll = new ScrollView(ActFmLoginActivity.this);
            bodyScroll.addView(body);

            new AlertDialog.Builder(ActFmLoginActivity.this).setView(
                    bodyScroll).setIcon(R.drawable.icon_32).setTitle(
                    R.string.actfm_ALA_login_title).setPositiveButton(
                    android.R.string.ok, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dlg, int which) {
                            AndroidUtilities.hideSoftInputForViews(ActFmLoginActivity.this, email, password);
                            authenticate(email.getText().toString(),
                                    "", "", ActFmInvoker.PROVIDER_PASSWORD,  //$NON-NLS-1$//$NON-NLS-2$
                                    password.getText().toString());
                            StatisticsService.reportEvent(StatisticsConstants.ACTFM_LOGIN_PW);
                        }
                    }).setNegativeButton(android.R.string.cancel, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dlg, int which) {
                            AndroidUtilities.hideSoftInputForViews(ActFmLoginActivity.this, email, password);
                        }
                    }).show();
        }
    };

    private void forgotPassword(final String email) {
        if (TextUtils.isEmpty(email)) {
            DialogUtilities.okDialog(this, getString(R.string.actfm_ALA_enter_email), null);
        } else {
            final ProgressDialog pd = DialogUtilities.progressDialog(this, getString(R.string.DLG_please_wait));
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        actFmInvoker.invoke("user_reset_password", "email", email); //$NON-NLS-1$ //$NON-NLS-2$
                        DialogUtilities.okDialog(ActFmLoginActivity.this, getString(R.string.actfm_ALA_reset_sent, email), null);
                    } catch (IOException e) {
                        handleError(e);
                    } finally {
                        DialogUtilities.dismissDialog(ActFmLoginActivity.this, pd);
                    }
                }
            }).start();
        }
    }

    private String generateRandomPassword() {
        String acceptable = "abcdefghijklmnopqrstuvwxyz1234567890"; //$NON-NLS-1$
        char[] chars = new char[8];
        char last = 'a';
        for (int i = 0; i < chars.length; i++) {
            char r = acceptable.charAt(rand.nextInt(acceptable.length()));
            while (!checkSimilar(last, r))
                r = acceptable.charAt(rand.nextInt(acceptable.length()));
            last = r;
            chars[i] = r;
        }
        return new String(chars);
    }

    @SuppressWarnings("nls")
    private boolean checkSimilar(char last, char check) {
        String iSimilar = "ijl1!";
        String oSimilar = "oO0";
        String puncSimilar = ".,";

        boolean match =  (iSimilar.indexOf(last) > 0 && iSimilar.indexOf(check) > 0)
                        || (oSimilar.indexOf(last) > 0 && oSimilar.indexOf(check) > 0)
                        || (puncSimilar.indexOf(last) > 0 && puncSimilar.indexOf(check) > 0);

        if (match)
            return false;
        return true;
    }

    private EditText addEditField(LinearLayout body, int hint) {
        TextView label = new TextView(ActFmLoginActivity.this);
        label.setText(hint);
        body.addView(label);
        EditText field = new EditText(ActFmLoginActivity.this);
        field.setHint(hint);
        body.addView(field);
        return field;
    }

    // --- facebook handler

    public void onFBAuthSucceed() {
        createUserAccountFB();
    }

    public void onFBAuthFail(String error) {
        DialogUtilities.okDialog(this, getString(R.string.actfm_ALA_title),
                android.R.drawable.ic_dialog_alert, error, null);
    }

    @Override
    public void onFBAuthCancel() {
        // do nothing
    }

    private ProgressDialog progressDialog;

    /**
     * Create user account via FB
     */
    public void createUserAccountFB() {
        progressDialog = DialogUtilities.progressDialog(this,
                getString(R.string.DLG_please_wait));
        facebookRunner.request("me", new SLARequestListener()); //$NON-NLS-1$
    }

    private class SLARequestListener implements RequestListener {

        @Override
        public void onComplete(String response, Object state) {
            JSONObject json;
            try {
                json = Util.parseJson(response);
                String firstName = json.getString("first_name"); //$NON-NLS-1$
                String lastName = json.getString("last_name"); //$NON-NLS-1$
                String email = json.getString("email"); //$NON-NLS-1$

                authenticate(email, firstName, lastName, ActFmInvoker.PROVIDER_FACEBOOK,
                        facebook.getAccessToken());
                StatisticsService.reportEvent(StatisticsConstants.ACTFM_LOGIN_FB);
            } catch (FacebookError e) {
                handleError(e);
            } catch (JSONException e) {
                handleError(e);
            }
        }

        @Override
        public void onFacebookError(FacebookError e, Object state) {
            handleError(e);
        }

        @Override
        public void onFileNotFoundException(FileNotFoundException e,
                Object state) {
            handleError(e);
        }

        @Override
        public void onIOException(IOException e, Object state) {
            handleError(e);
        }

        @Override
        public void onMalformedURLException(MalformedURLException e,
                Object state) {
            handleError(e);
        }

    }

    // --- utilities

    @SuppressWarnings("nls")
    public void authenticate(final String email, final String firstName, final String lastName, final String provider,
            final String secret) {
        if (progressDialog == null)
            progressDialog = DialogUtilities.progressDialog(this,
                    getString(R.string.DLG_please_wait));

        new Thread() {
            @Override
            public void run() {
                try {
                    final JSONObject result = actFmInvoker.authenticate(email, firstName, lastName,
                            provider, secret);
                    final String token = actFmInvoker.getToken();

                    if (result.optBoolean("new")) { // Report new user statistic
                        StatisticsService.reportEvent(StatisticsConstants.ACTFM_NEW_USER, "provider", provider);
                    }
                    // Successful login, create outstanding entries
                    long lastId = Preferences.getLong(ActFmPreferenceService.PREF_USER_ID, 0);

                    if (!TextUtils.isEmpty(token) && lastId == 0) {
                        constructOutstandingTables();
                    }
                    runOnUiThread(new Runnable() {
                        public void run() {
                            DialogUtilities.dismissDialog(ActFmLoginActivity.this, progressDialog);
                            progressDialog = null;
                            postAuthenticate(result, token);
                        }
                    });
                } catch (IOException e) {
                    handleError(e);
                } finally {
                    runOnUiThread(new Runnable() {
                        public void run() {
                            if (progressDialog != null) {
                                DialogUtilities.dismissDialog(ActFmLoginActivity.this, progressDialog);
                            }
                        }
                    });
                }
            }
        }.start();
    }

    private void constructOutstandingTables() {
        new ConstructTaskOutstandingTableFromMasterTable(NameMaps.TABLE_ID_TASKS, taskDao, taskOutstandingDao, metadataDao, Task.CREATION_DATE).execute();
        new ConstructOutstandingTableFromMasterTable<TagData, TagOutstanding>(NameMaps.TABLE_ID_TAGS, tagDataDao, tagOutstandingDao, TagData.CREATION_DATE).execute();
        new ConstructOutstandingTableFromMasterTable<UserActivity, UserActivityOutstanding>(NameMaps.TABLE_ID_USER_ACTIVITY, userActivityDao, userActivityOutstandingDao, UserActivity.CREATED_AT).execute();
        new ConstructOutstandingTableFromMasterTable<TaskListMetadata, TaskListMetadataOutstanding>(NameMaps.TABLE_ID_TASK_LIST_METADATA, taskListMetadataDao, taskListMetadataOutstandingDao, null).execute();
    }

    @SuppressWarnings("nls")
    private void postAuthenticate(final JSONObject result, final String token) {
        long lastLoggedInUser = Preferences.getLong(ActFmPreferenceService.PREF_USER_ID, 0);

        if (lastLoggedInUser > 0) {
            long newUserId = result.optLong("id");
            if (lastLoggedInUser != newUserId) {
                // In this case, we need to either make all data private or clear all data
                // Prompt for choice
                DialogUtilities.okCancelCustomDialog(this,
                        getString(R.string.actfm_logged_in_different_user_title),
                        getString(R.string.actfm_logged_in_different_user_body),
                        R.string.actfm_logged_in_different_user_clear_data,
                        R.string.actfm_logged_in_different_user_keep_data,
                        android.R.drawable.ic_dialog_alert,
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                deleteDatabase(database.getName());
                                finishSignIn(result, token, true);
                            }
                        },
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                final ProgressDialog pd = DialogUtilities.progressDialog(ActFmLoginActivity.this, getString(R.string.actfm_logged_in_different_user_processing));

                                new Thread(new Runnable() {
                                    @Override
                                    public void run() {
                                        rebuildAllSyncData();
                                        runOnUiThread(new Runnable() {
                                            @Override
                                            public void run() {
                                                finishSignIn(result, token, true);
                                            }
                                        });
                                        pd.dismiss();
                                    }
                                }).start();
                            }
                        });
            } else {
                finishSignIn(result, token, false);
            }
        } else {
            finishSignIn(result, token, false);
        }
    }

    private void rebuildAllSyncData() {
        // Delete all tasks not assigned to self
        taskService.deleteWhere(Criterion.or(Task.USER_ID.neq(0), Task.DELETION_DATE.gt(0)));
        // Delete user table
        userDao.deleteWhere(Criterion.all);
        // Delete attachments table
        taskAttachmentDao.deleteWhere(Criterion.all);
        // Delete deleted tags
        tagDataDao.deleteWhere(TagData.DELETION_DATE.gt(0));
        // Delete deleted metadata
        metadataDao.deleteWhere(Metadata.DELETION_DATE.gt(0));

        // Clear all outstanding tables
        taskOutstandingDao.deleteWhere(Criterion.all);
        tagOutstandingDao.deleteWhere(Criterion.all);
        userActivityOutstandingDao.deleteWhere(Criterion.all);
        taskListMetadataOutstandingDao.deleteWhere(Criterion.all);
        taskAttachmentOutstandingDao.deleteWhere(Criterion.all);

        // Make all tags private
        tagMetadataDao.deleteWhere(Criterion.all);

        // Generate new uuids for all tasks/tags/user activity/task list metadata and update links
        generateNewUuids();
        clearTablePushedAtValues();

        constructOutstandingTables();
    }

    private void generateNewUuids() {
        final HashMap<String, String> uuidTaskMap = new HashMap<String, String>();
        HashMap<String, String> uuidTagMap = new HashMap<String, String>();
        HashMap<String, String> uuidUserActivityMap = new HashMap<String, String>();
        HashMap<String, String> uuidTaskListMetadataMap = new HashMap<String, String>();

        mapUuids(taskDao, uuidTaskMap);
        mapUuids(tagDataDao, uuidTagMap);
        mapUuids(userActivityDao, uuidUserActivityMap);
        mapUuids(taskListMetadataDao, uuidTaskListMetadataMap);

        Task t = new Task();
        TagData td = new TagData();
        Metadata m = new Metadata();
        UserActivity ua = new UserActivity();
        TaskListMetadata tlm = new TaskListMetadata();

        Set<Entry<String, String>> entries = uuidTaskMap.entrySet();
        for (Entry<String, String> e : entries) {
            t.clear();
            m.clear();
            ua.clear();

            String oldUuid = e.getKey();
            String newUuid = e.getValue();

            t.setValue(Task.UUID, newUuid);
            t.setValue(Task.PUSHED_AT, 0L);
            t.setValue(Task.ATTACHMENTS_PUSHED_AT, 0L);
            t.setValue(Task.USER_ACTIVITIES_PUSHED_AT, 0L);
            t.setValue(Task.HISTORY_FETCH_DATE, 0L);
            ua.setValue(UserActivity.TARGET_ID, newUuid);
            m.setValue(TaskToTagMetadata.TASK_UUID, newUuid);

            taskDao.update(Task.UUID.eq(oldUuid), t);
            metadataDao.update(Criterion.and(MetadataCriteria.withKey(TaskToTagMetadata.KEY), TaskToTagMetadata.TASK_UUID.eq(oldUuid)), m);
            userActivityDao.update(UserActivity.TARGET_ID.eq(oldUuid), ua);
        }

        entries = uuidTagMap.entrySet();
        for (Entry<String, String> e : entries) {
            td.clear();
            ua.clear();
            m.clear();
            tlm.clear();

            String oldUuid = e.getKey();
            String newUuid = e.getValue();

            td.setValue(TagData.UUID, newUuid);
            td.setValue(TagData.PUSHED_AT, 0L);
            td.setValue(TagData.TASKS_PUSHED_AT, 0L);
            td.setValue(TagData.METADATA_PUSHED_AT, 0L);
            td.setValue(TagData.USER_ACTIVITIES_PUSHED_AT, 0L);
            ua.setValue(UserActivity.TARGET_ID, newUuid);
            m.setValue(TaskToTagMetadata.TAG_UUID, newUuid);
            tlm.setValue(TaskListMetadata.TAG_UUID, newUuid);

            tagDataDao.update(TagData.UUID.eq(oldUuid), td);
            userActivityDao.update(UserActivity.TARGET_ID.eq(oldUuid), ua);
            metadataDao.update(Criterion.and(MetadataCriteria.withKey(TaskToTagMetadata.KEY), TaskToTagMetadata.TAG_UUID.eq(oldUuid)), m);
            taskListMetadataDao.update(TaskListMetadata.TAG_UUID.eq(oldUuid), tlm);
        }

        entries = uuidUserActivityMap.entrySet();
        for (Entry<String, String> e : entries) {
            ua.clear();

            String oldUuid = e.getKey();
            String newUuid = e.getValue();

            ua.setValue(UserActivity.UUID, newUuid);
            ua.setValue(UserActivity.PUSHED_AT, 0L);
            userActivityDao.update(UserActivity.UUID.eq(oldUuid), ua);
        }

        TodorooCursor<TaskListMetadata> tlmCursor = taskListMetadataDao.query(Query.select(TaskListMetadata.ID, TaskListMetadata.UUID, TaskListMetadata.TASK_IDS, TaskListMetadata.CHILD_TAG_IDS));
        try {
            for (tlmCursor.moveToFirst(); !tlmCursor.isAfterLast(); tlmCursor.moveToNext()) {
                tlm.clear();
                tlm.readFromCursor(tlmCursor);
                tlm.setValue(TaskListMetadata.UUID, uuidTaskListMetadataMap.get(tlm.getUuid()));
                tlm.setValue(TaskListMetadata.PUSHED_AT, 0L);
                String taskIds = tlm.getValue(TaskListMetadata.TASK_IDS);
                if (!TaskListMetadata.taskIdsIsEmpty(taskIds)) {
                    Node root = AstridOrderedListUpdater.buildTreeModel(taskIds, null);
                    SubtasksHelper.remapTree(root, uuidTaskMap, new TreeRemapHelper<String>() {
                        public String getKeyFromOldUuid(String uuid) {
                            return uuid; // Old uuids are the keys
                        }
                    });
                    taskIds = AstridOrderedListUpdater.serializeTree(root);
                    tlm.setValue(TaskListMetadata.TASK_IDS, taskIds);
                }
                taskListMetadataDao.saveExisting(tlm);
            }
        } finally {
            tlmCursor.close();
        }

    }

    private void clearTablePushedAtValues() {
        String[] pushedAtPrefs = new String[] { NameMaps.PUSHED_AT_TASKS, NameMaps.PUSHED_AT_TAGS,
                NameMaps.PUSHED_AT_ACTIVITY, NameMaps.PUSHED_AT_USERS, NameMaps.PUSHED_AT_TASK_LIST_METADATA };
        for (String key : pushedAtPrefs)
            Preferences.clear(key);
    }

    private <T extends RemoteModel> void mapUuids(RemoteModelDao<T> dao, HashMap<String, String> map) {
        TodorooCursor<T> items = dao.query(Query.select(RemoteModel.UUID_PROPERTY));
        try {
            for (items.moveToFirst(); !items.isAfterLast(); items.moveToNext()) {
                map.put(items.get(RemoteModel.UUID_PROPERTY), UUIDHelper.newUUID());
            }
        } finally {
            items.close();
        }
    }

    @SuppressWarnings("nls")
    private void finishSignIn(JSONObject result, String token, boolean restart) {
        actFmPreferenceService.setToken(token);

        Preferences.setLong(ActFmPreferenceService.PREF_USER_ID,
                result.optLong("id"));
        Preferences.setString(ActFmPreferenceService.PREF_NAME,
                result.optString("name"));
        Preferences.setString(ActFmPreferenceService.PREF_FIRST_NAME,
                result.optString("first_name"));
        Preferences.setString(ActFmPreferenceService.PREF_LAST_NAME,
                result.optString("last_name"));
        Preferences.setBoolean(ActFmPreferenceService.PREF_PREMIUM,
                result.optBoolean("premium"));
        Preferences.setString(ActFmPreferenceService.PREF_EMAIL,
                result.optString("email"));
        Preferences.setString(ActFmPreferenceService.PREF_PICTURE,
                result.optString("picture"));

        ActFmPreferenceService.reloadThisUser();

        GCMIntentService.register(this);


        if (restart) {
            System.exit(0);
            return;
        } else {
            setResult(RESULT_OK);
            finish();
        }

        ActFmSyncMonitor monitor = ActFmSyncMonitor.getInstance();
        synchronized (monitor) {
            monitor.notifyAll();
        }
    }

    @SuppressWarnings("nls")
    private void handleError(final Throwable e) {
        DialogUtilities.dismissDialog(this, progressDialog);
        exceptionService.reportError("astrid-sharing-login", e); //$NON-NLS-1$

        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                String message = e.getMessage();
                if (e instanceof ActFmServiceException) {
                    ActFmServiceException ae = (ActFmServiceException) e;
                    JSONObject result = ae.result;
                    if (result != null && result.has("code")) {
                        String code = result.optString("code");
                        if ("user_exists".equals(code))
                            message = getString(R.string.actfm_ALA_error_user_exists);
                        else if ("incorrect_password".equals(code))
                            message = getString(R.string.actfm_ALA_error_wrong_password);
                        else if ("user_not_found".equals(code))
                            message = getString(R.string.actfm_ALA_error_user_not_found);
                    }
                }
                errors.setText(message);
                errors.setVisibility(View.VISIBLE);
            }
        });
    }

    // --- google account manager

    @SuppressWarnings("nls")
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {

        if (resultCode == RESULT_CANCELED)
            return;

        if (requestCode == REQUEST_CODE_GOOGLE_ACCOUNTS && data != null && credentialsListener != null) {
            String accounts[] = data.getStringArrayExtra(
                    GoogleLoginServiceConstants.ACCOUNTS_KEY);
            credentialsListener.getCredentials(accounts);
        } else if (requestCode == LoginButton.REQUEST_CODE_FACEBOOK) {
            if (data == null)
                return;

            String error = data.getStringExtra("error");
            if (error == null) {
                error = data.getStringExtra("error_type");
            }
            String token = data.getStringExtra("access_token");
            if (error != null) {
                onFBAuthFail(error);
            } else if (token == null) {
                onFBAuthFail("Something went wrong! Please try again.");
            } else {
                facebook.setAccessToken(token);
                onFBAuthSucceed();
            }
            errors.setVisibility(View.GONE);
        } else if (requestCode == REQUEST_CODE_GOOGLE) {
            if (data == null)
                return;
            String email = data.getStringExtra(ActFmGoogleAuthActivity.RESULT_EMAIL);
            String token = data.getStringExtra(ActFmGoogleAuthActivity.RESULT_TOKEN);
            authenticate(email, email, "", "google", token);
        }
    }

    public interface OnGetCredentials {
        public void getCredentials(String[] accounts);
    }

    private OnGetCredentials credentialsListener;

    public void getCredentials(OnGetCredentials onGetCredentials) {
        credentialsListener = onGetCredentials;
        if (Integer.parseInt(Build.VERSION.SDK) >= 7)
            credentialsListener.getCredentials(ModernAuthManager.getAccounts(this));
        else
            GoogleLoginServiceHelper.getAccount(this,
                    REQUEST_CODE_GOOGLE_ACCOUNTS, false);
    }

}
