/****************************************************************************************
 * Copyright (c) 2015 Ryan Annis <squeenix@live.ca>                                     *
 * Copyright (c) 2015 Timothy Rae <perceptualchaos2@gmail.com>                          *
 *                                                                                      *
 * This program is free software; you can redistribute it and/or modify it under        *
 * the terms of the GNU General Public License as published by the Free Software        *
 * Foundation; either version 3 of the License, or (at your option) any later           *
 * version.                                                                             *
 *                                                                                      *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY      *
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A      *
 * PARTICULAR PURPOSE. See the GNU General Public License for more details.             *
 *                                                                                      *
 * You should have received a copy of the GNU General Public License along with         *
 * this program.  If not, see <http://www.gnu.org/licenses/>.                           *
 ****************************************************************************************/
package com.ichi2.anki;

import android.os.Build;
import android.os.Bundle;

import android.text.InputType;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ListView;

import com.afollestad.materialdialogs.MaterialDialog;
import com.ichi2.anim.ActivityTransitionAnimation;
import com.ichi2.anki.dialogs.ConfirmationDialog;
import com.ichi2.anki.dialogs.LocaleSelectionDialog;
import com.ichi2.anki.dialogs.ModelEditorContextMenu;
import com.ichi2.anki.exception.ConfirmModSchemaException;
import com.ichi2.async.CollectionTask;
import com.ichi2.async.TaskListenerWithContext;
import com.ichi2.async.TaskListener;
import com.ichi2.async.Task;
import com.ichi2.libanki.Collection;
import com.ichi2.libanki.Model;
import com.ichi2.themes.StyledProgressDialog;
import com.ichi2.widget.WidgetStatus;

import com.ichi2.utils.JSONArray;
import com.ichi2.utils.JSONException;
import com.ichi2.utils.JSONObject;

import java.util.ArrayList;
import com.ichi2.async.TaskData;
import java.util.Locale;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.fragment.app.DialogFragment;
import timber.log.Timber;

public class ModelFieldEditor extends AnkiActivity implements LocaleSelectionDialog.LocaleSelectionDialogHandler {

    private final static int NORMAL_EXIT = 100001;

    //Position of the current field selected
    private int mCurrentPos;

    private ListView mFieldLabelView;
    private ArrayList<String> mFieldLabels;
    private MaterialDialog mProgressDialog;

    private Collection mCol;
    private JSONArray mNoteFields;
    private Model mMod;

    private ModelEditorContextMenu mContextMenu;
    private EditText mFieldNameInput;

    private Runnable mConfirmDialogCancel = () -> dismissContextMenu();

    // ----------------------------------------------------------------------------
    // ANDROID METHODS
    // ----------------------------------------------------------------------------


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.model_field_editor);

        mFieldLabelView = findViewById(R.id.note_type_editor_fields);
        enableToolbar();

        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(R.string.model_field_editor_title);
            getSupportActionBar().setSubtitle(getIntent().getStringExtra("title"));
        }
        startLoadingCollection();
    }


    @Override
    protected void onStop() {
        super.onStop();
        if (!isFinishing()) {
            WidgetStatus.update(this);
            UIUtils.saveCollectionInBackground();
        }
    }


    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        super.onCreateOptionsMenu(menu);
        getMenuInflater().inflate(R.menu.model_editor, menu);
        return true;
    }


    // ----------------------------------------------------------------------------
    // ANKI METHODS
    // ----------------------------------------------------------------------------


    @Override
    protected void onCollectionLoaded(Collection col) {
        super.onCollectionLoaded(col);
        this.mCol = col;
        setupLabels();
        createfieldLabels();
    }


    // ----------------------------------------------------------------------------
    // UI SETUP
    // ----------------------------------------------------------------------------


    /*
     * Sets up the main ListView and ArrayAdapters
     * Containing clickable labels for the fields
     */
    private void createfieldLabels() {
        ArrayAdapter<String> mFieldLabelAdapter = new ArrayAdapter<>(this, R.layout.model_field_editor_list_item, mFieldLabels);
        mFieldLabelView.setAdapter(mFieldLabelAdapter);
        mFieldLabelView.setOnItemClickListener((parent, view, position, id) -> {
            mContextMenu = ModelEditorContextMenu.newInstance(mFieldLabels.get(position), mContextMenuListener);
            showDialogFragment(mContextMenu);
            mCurrentPos = position;
        });
    }


    /*
      * Sets up the ArrayList containing the text for the main ListView
      */
    private void setupLabels() {
        long noteTypeID = getIntent().getLongExtra("noteTypeID", 0);
        mMod = mCol.getModels().get(noteTypeID);

        mFieldLabels = new ArrayList<>();
        mNoteFields = mMod.getJSONArray("flds");
        for (int i = 0; i < mNoteFields.length(); i++) {
            JSONObject o = mNoteFields.getJSONObject(i);
            mFieldLabels.add(o.getString("name"));
        }
    }


    // ----------------------------------------------------------------------------
    // CONTEXT MENU DIALOGUES
    // ----------------------------------------------------------------------------

    private static class AddFieldTask implements Task {
        private final String mFieldName;
        private final Model mModel;
        public AddFieldTask(Model model, String fieldName) {
            this.mFieldName = fieldName;
            this.mModel = model;
        }
        public TaskData background(CollectionTask collectionTask)  {
            Timber.d("doInBackgroundRepositionField");

            Collection col = collectionTask.getCol();
            col.getModels().addFieldModChanged(mModel, col.getModels().newField(mFieldName));
            col.save();
            return new TaskData(true);
        }
    }


    /*
    * Creates a dialog to create a field
    */
    private void addFieldDialog() {
        mFieldNameInput = new EditText(this);
        mFieldNameInput.setSingleLine(true);

        new MaterialDialog.Builder(this)
                .title(R.string.model_field_editor_add)
                .positiveText(R.string.dialog_ok)
                .customView(mFieldNameInput, true)
                .onPositive((dialog, which) -> {
                    String fieldName = mFieldNameInput.getText().toString()
                            .replaceAll("[\\n\\r]", "");

                    if (fieldName.length() == 0) {
                        UIUtils.showThemedToast(this, getResources().getString(R.string.toast_empty_name), true);
                    } else if (containsField(fieldName)) {
                        UIUtils.showThemedToast(this, getResources().getString(R.string.toast_duplicate_field), true);
                    } else {
                        //Name is valid, now field is added
                        changeHandler listener = changeFieldHandler();
                        AddFieldTask task = new AddFieldTask(mMod, fieldName);
                        try {
                            mCol.modSchema();
                            CollectionTask.launchCollectionTask(listener, task);
                        } catch (ConfirmModSchemaException e) {

                            //Create dialogue to for schema change
                            ConfirmationDialog c = new ConfirmationDialog();
                            c.setArgs(getResources().getString(R.string.full_sync_confirmation));
                            Runnable confirm = () -> {
                                mCol.modSchemaNoCheck();
                                String fieldName1 = mFieldNameInput.getText().toString()
                                        .replaceAll("[\\n\\r]", "");
                                CollectionTask.launchCollectionTask(listener, task);
                                dismissContextMenu();
                            };

                            c.setConfirm(confirm);
                            c.setCancel(mConfirmDialogCancel);
                            ModelFieldEditor.this.showDialogFragment(c);
                        }
                        mCol.getModels().update(mMod);
                        fullRefreshList();
                    }
                })
                .negativeText(R.string.dialog_cancel)
                .show();
    }


    /*
     * Creates a dialog to delete the currently selected field
     */
    private void deleteFieldDialog() {
        Runnable confirm = () -> {
            mCol.modSchemaNoCheck();
            deleteField();
            dismissContextMenu();
        };


        if (mFieldLabels.size() < 2) {
            UIUtils.showThemedToast(this, getResources().getString(R.string.toast_last_field), true);
        } else {
            try {
                mCol.modSchema();
                ConfirmationDialog d = new ConfirmationDialog();
                d.setArgs(getResources().getString(R.string.field_delete_warning));
                d.setConfirm(confirm);
                d.setCancel(mConfirmDialogCancel);
                showDialogFragment(d);
            } catch (ConfirmModSchemaException e) {
                ConfirmationDialog c = new ConfirmationDialog();
                c.setConfirm(confirm);
                c.setCancel(mConfirmDialogCancel);
                c.setArgs(getResources().getString(R.string.full_sync_confirmation));
                showDialogFragment(c);
            }
        }
    }

    private static class DeleteFieldTask implements Task {
        private final Model mModel;
        private final JSONObject mField;
        public DeleteFieldTask(Model mModel, JSONObject mField) {
            this.mModel = mModel;
            this.mField = mField;
        }

        public TaskData background(CollectionTask collectionTask) {
            Timber.d("doInBackGroundDeleteField");


            Collection col = collectionTask.getCol();
            try {
                col.getModels().remField(mModel, mField);
                col.save();
            } catch (ConfirmModSchemaException e) {
                //Should never be reached
                return new TaskData(false);
            }
            return new TaskData(true);
        }
    }

    private void deleteField() {
        JSONObject field = mNoteFields.getJSONObject(mCurrentPos);
        CollectionTask.launchCollectionTask(changeFieldHandler(), new DeleteFieldTask(mMod, field));
    }


    /*
     * Creates a dialog to rename the currently selected field
     * Processing time is constant
     */
    private void renameFieldDialog() {
        mFieldNameInput = new EditText(this);
        mFieldNameInput.setSingleLine(true);
        mFieldNameInput.setText(mFieldLabels.get(mCurrentPos));
        mFieldNameInput.setSelection(mFieldNameInput.getText().length());
        new MaterialDialog.Builder(this)
                .title(R.string.rename_model)
                .positiveText(R.string.rename)
                .customView(mFieldNameInput, true)
                .onPositive((dialog, which) -> {

                        String fieldLabel = mFieldNameInput.getText().toString()
                                .replaceAll("[\\n\\r]", "");
                        if (fieldLabel.length() == 0) {
                            UIUtils.showThemedToast(this, getResources().getString(R.string.toast_empty_name), true);
                        } else if (containsField(fieldLabel)) {
                            UIUtils.showThemedToast(this, getResources().getString(R.string.toast_duplicate_field), true);
                        } else {
                            //Field is valid, now rename
                            try {
                                renameField();
                            } catch (ConfirmModSchemaException e) {

                                // Handler mod schema confirmation
                                ConfirmationDialog c = new ConfirmationDialog();
                                c.setArgs(getResources().getString(R.string.full_sync_confirmation));
                                Runnable confirm = () -> {
                                    mCol.modSchemaNoCheck();
                                    try {
                                        renameField();
                                    } catch (ConfirmModSchemaException e1) {
                                        //This should never be thrown
                                    }
                                    dismissContextMenu();
                                };
                                c.setConfirm(confirm);
                                c.setCancel(mConfirmDialogCancel);
                                ModelFieldEditor.this.showDialogFragment(c);
                            }
                        }
                    })
                .negativeText(R.string.dialog_cancel)
                .show();
    }

    private static class RepositionFieldDialogTask implements Task {
        private final Model mModel;
        private final JSONObject mField;
        private final int mIndex;

        public RepositionFieldDialogTask(Model mModel, JSONObject mField, int mIndex) {
            this.mModel = mModel;
            this.mField = mField;
            this.mIndex = mIndex;
        }

        public TaskData background(CollectionTask collectionTask) {
            Timber.d("doInBackgroundRepositionField");
            Collection col = collectionTask.getCol();
            try {
                col.getModels().moveField(mModel, mField, mIndex);
                col.save();
            } catch (ConfirmModSchemaException e) {
                //Should never be reached
                return new TaskData(false);
            }
            return new TaskData(true);
        }
    }

    /*
     * Allows the user to select a number less than the number of fields in the current model to
     * reposition the current field to
     * Processing time is scales with number of items
     */
    private void repositionFieldDialog() {
        mFieldNameInput = new EditText(this);
        mFieldNameInput.setRawInputType(InputType.TYPE_CLASS_NUMBER);
        new MaterialDialog.Builder(this)
                .title(String.format(getResources().getString(R.string.model_field_editor_reposition), 1, mFieldLabels.size()))
                .positiveText(R.string.dialog_ok)
                .customView(mFieldNameInput, true)
                .onPositive((dialog, which) -> {
                        String newPosition = mFieldNameInput.getText().toString();
                        int pos;
                        try {
                            pos = Integer.parseInt(newPosition);
                        } catch (NumberFormatException n) {
                            UIUtils.showThemedToast(this, getResources().getString(R.string.toast_out_of_range), true);
                            return;
                        }

                        if (pos < 1 || pos > mFieldLabels.size()) {
                            UIUtils.showThemedToast(this, getResources().getString(R.string.toast_out_of_range), true);
                        } else {
                            changeHandler listener = changeFieldHandler();
                            // Input is valid, now attempt to modify
                            JSONObject field = mNoteFields.getJSONObject(mCurrentPos);
                            /**
                             * Repositions the given field in the given model
                             */
                            RepositionFieldDialogTask task = new RepositionFieldDialogTask(mMod, field, pos - 1);
                            try {
                                mCol.modSchema();
                                CollectionTask.launchCollectionTask(listener, task);
                            } catch (ConfirmModSchemaException e) {

                                // Handle mod schema confirmation
                                ConfirmationDialog c = new ConfirmationDialog();
                                c.setArgs(getResources().getString(R.string.full_sync_confirmation));
                                Runnable confirm = () -> {
                                    try {
                                        mCol.modSchemaNoCheck();
                                        String newPosition1 = mFieldNameInput.getText().toString();
                                        int pos1 = Integer.parseInt(newPosition1);
                                        CollectionTask.launchCollectionTask(listener, task);
                                        dismissContextMenu();
                                    } catch (JSONException e1) {
                                        throw new RuntimeException(e1);
                                    }
                                };
                                c.setConfirm(confirm);
                                c.setCancel(mConfirmDialogCancel);
                                ModelFieldEditor.this.showDialogFragment(c);
                            }
                        }
                    })
                .negativeText(R.string.dialog_cancel)
                .show();
    }


    // ----------------------------------------------------------------------------
    // HELPER METHODS
    // ----------------------------------------------------------------------------


    /*
     * Useful when a confirmation dialog is created within another dialog
     */
    private void dismissContextMenu() {
        if (mContextMenu != null) {
            mContextMenu.dismiss();
            mContextMenu = null;
        }
    }


    private void dismissProgressBar() {
        if (mProgressDialog != null) {
            mProgressDialog.dismiss();
        }
        mProgressDialog = null;
    }


    /*
     * Renames the current field
     */
    private void renameField() throws ConfirmModSchemaException {
        String fieldLabel = mFieldNameInput.getText().toString()
                .replaceAll("[\\n\\r]", "");
        JSONObject field = mNoteFields.getJSONObject(mCurrentPos);
        mCol.getModels().renameField(mMod, field, fieldLabel);
        mCol.getModels().save();
        fullRefreshList();
    }

    private static class SortByFieldTask implements Task {
        private final Model mModel;
        private final int mIdx;
        public SortByFieldTask(Model model, int idx) {
            this.mModel = model;
            this.mIdx = idx;
        }
        public TaskData background(CollectionTask collectionTask)  {
            try {
                Timber.d("doInBackgroundChangeSortField");
                Collection col = collectionTask.getCol();
                col.getModels().setSortIdx(mModel, mIdx);
                col.save();
            } catch (Exception e) {
                Timber.e(e, "Error changing sort field");
                return new TaskData(false);
            }
            return new TaskData(true);
        }
    }


    /*
     * Changes the sort field (that displays in card browser) to the current field
     */
    private void sortByField() {
        changeHandler listener = changeFieldHandler();
        SortByFieldTask task = new SortByFieldTask(mMod, mCurrentPos);
        try {
            mCol.modSchema();
            CollectionTask.launchCollectionTask(listener, task);
        } catch (ConfirmModSchemaException e) {
            // Handler mMod schema confirmation
            ConfirmationDialog c = new ConfirmationDialog();
            c.setArgs(getResources().getString(R.string.full_sync_confirmation));
            Runnable confirm = () -> {
                mCol.modSchemaNoCheck();
                CollectionTask.launchCollectionTask(listener, task);
                dismissContextMenu();
            };
            c.setConfirm(confirm);
            c.setCancel(mConfirmDialogCancel);
            ModelFieldEditor.this.showDialogFragment(c);
        }
    }

    /*
     * Toggle the "Remember last input" setting AKA the "Sticky" setting
     */
    private void toggleStickyField() {
        // Get the current field
        JSONObject field = mNoteFields.getJSONObject(mCurrentPos);
        // If the sticky setting is enabled then disable it, otherwise enable it
        if (field.getBoolean("sticky")) {
            field.put("sticky", false);
        } else {
            field.put("sticky", true);
        }
    }


    /*
     * Reloads everything
     */
    private void fullRefreshList() {
        setupLabels();
        createfieldLabels();
    }


    /*
     * Checks if there exists a field with this name in the current model
     */
    private boolean containsField(String field) {
        for (String s : mFieldLabels) {
            if (field.compareTo(s) == 0) {
                return true;
            }
        }
        return false;
    }


    // ----------------------------------------------------------------------------
    // HANDLERS
    // ----------------------------------------------------------------------------


    /*
     * Called during the desk task when any field is modified
     */
    private changeHandler changeFieldHandler() {
        return new changeHandler(this);
    }
    private static class changeHandler extends TaskListenerWithContext<ModelFieldEditor> {
        public changeHandler(ModelFieldEditor modelFieldEditor) {
            super(modelFieldEditor);
        }

        @Override
        public void actualOnPreExecute(@NonNull ModelFieldEditor modelFieldEditor) {
            if (modelFieldEditor != null && modelFieldEditor.mProgressDialog == null) {
                modelFieldEditor.mProgressDialog = StyledProgressDialog.show(modelFieldEditor, modelFieldEditor.getIntent().getStringExtra("title"),
                        modelFieldEditor.getResources().getString(R.string.model_field_editor_changing), false);
            }
        }

        @Override
        public void actualOnPostExecute(@NonNull ModelFieldEditor modelFieldEditor, TaskData result) {
            if (!result.getBoolean()) {
                modelFieldEditor.closeActivity(DeckPicker.RESULT_DB_ERROR);
            }

            modelFieldEditor.dismissProgressBar();
            modelFieldEditor.fullRefreshList();
        }
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case android.R.id.home:
                onBackPressed();
                return true;
            case R.id.action_add_new_model:
                addFieldDialog();
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }


    public void closeActivity() {
        closeActivity(NORMAL_EXIT);
    }


    private void closeActivity(int reason) {
        switch (reason) {
            case NORMAL_EXIT:
                finishWithAnimation(ActivityTransitionAnimation.RIGHT);
                break;
            default:
                finishWithAnimation(ActivityTransitionAnimation.RIGHT);
                break;
        }
    }


    @Override
    public void onBackPressed() {
        closeActivity();
    }


    private MaterialDialog.ListCallback mContextMenuListener = (materialDialog, view, selection, charSequence) -> {
        switch (selection) {
            case ModelEditorContextMenu.SORT_FIELD:
                sortByField();
                break;
            case ModelEditorContextMenu.FIELD_REPOSITION:
                repositionFieldDialog();
                break;
            case ModelEditorContextMenu.FIELD_DELETE:
                deleteFieldDialog();
                break;
            case ModelEditorContextMenu.FIELD_RENAME:
                renameFieldDialog();
                break;
            case ModelEditorContextMenu.FIELD_TOGGLE_STICKY:
                toggleStickyField();
                break;
            default: {
                //need this as we can't switch on a @RequiresApi
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && selection == ModelEditorContextMenu.FIELD_ADD_LANGUAGE_HINT) {
                    Timber.i("displaying locale hint dialog");
                    localeHintDialog();
                    break;
                }
            }
        }
    };


    @RequiresApi(api = Build.VERSION_CODES.N)
    private void localeHintDialog() {
        //We don't currently show the current value, but we may want to in the future
        DialogFragment dialogFragment = LocaleSelectionDialog.newInstance(this);
        showDialogFragment(dialogFragment);
    }


    /*
     * Sets the Locale Hint of the field to the provided value.
     * This allows some keyboard (GBoard) to change language
     */
    @RequiresApi(api = Build.VERSION_CODES.N)
    private void addFieldLocaleHint(@NonNull Locale selectedLocale) {
        String input = selectedLocale.toLanguageTag();
        JSONObject field = (JSONObject) mNoteFields.get(mCurrentPos);
        field.put("ad-hint-locale", input);
        mCol.getModels().save();
        Timber.i("Set field locale to %s", selectedLocale);
        String format = getString(R.string.model_field_editor_language_hint_dialog_success_result, selectedLocale.getDisplayName());
        UIUtils.showSimpleSnackbar(this, format, true);
    }



    @Override
    @RequiresApi(api = Build.VERSION_CODES.N)
    public void onSelectedLocale(@NonNull Locale selectedLocale) {
        addFieldLocaleHint(selectedLocale);
        dismissAllDialogFragments();
    }


    @Override
    @RequiresApi(api = Build.VERSION_CODES.N)
    public void onLocaleSelectionCancelled() {
        dismissAllDialogFragments();
    }
}
